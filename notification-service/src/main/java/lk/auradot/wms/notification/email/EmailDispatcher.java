package lk.auradot.wms.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Sends queued emails. The mail server is called outside any transaction.
 * <ul>
 * <li>An email the server refuses waits (1 minute, then doubling, at most an hour) and is tried again, up to the
 *     configured number of attempts; after that it stays in the table with its last error.</li>
 * <li>A server that cannot be reached is not the email's fault: everything waits a minute and no attempt is counted,
 *     however long the outage lasts.</li>
 * <li>Delivery is at least once: if this instance dies after the server took an email and before that was recorded,
 *     the email is sent again when its hold runs out.</li>
 * </ul>
 */
@Component
public class EmailDispatcher {

    static final int BATCH = 50;
    /** How long one email is held while it is being sent; well above the SMTP timeouts in application.yml. */
    static final Duration HOLD = Duration.ofMinutes(2);
    static final Duration OUTAGE_PAUSE = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    private final EmailStore emails;
    private final Mailer mailer;
    private final EmailProperties props;
    private final Clock clock;

    public EmailDispatcher(EmailStore emails, Mailer mailer, EmailProperties props, Clock clock) {
        this.emails = emails;
        this.mailer = mailer;
        this.props = props;
        this.clock = clock;
    }

    /** Returns how many emails the mail server took. */
    public int sendPending() {
        List<QueuedEmail> due = emails.claim(BATCH, props.maxAttempts(), clock.instant(), HOLD);
        int sent = 0;
        for (int i = 0; i < due.size(); i++) {
            // The hold is renewed per email, so a slow batch cannot let it run out under an email being sent.
            Optional<QueuedEmail> held = emails.renew(due.get(i), clock.instant().plus(HOLD));
            if (held.isEmpty()) {
                continue; // another instance has taken it over
            }
            QueuedEmail email = held.get();
            try {
                mailer.send(email.to(), email.subject(), email.body());
            } catch (MailServerUnavailableException e) {
                Instant retryAt = clock.instant().plus(OUTAGE_PAUSE);
                emails.defer(email, e.getMessage(), retryAt);
                for (QueuedEmail waiting : due.subList(i + 1, due.size())) {
                    emails.defer(waiting, e.getMessage(), retryAt);
                }
                log.warn("The mail server cannot be reached; {} email(s) wait: {}", due.size() - i, e.getMessage());
                return sent;
            } catch (RuntimeException e) {
                int failed = email.attempts() + 1;
                emails.markFailed(email, e.getMessage(), clock.instant().plus(retryAfter(failed)));
                if (failed >= props.maxAttempts()) {
                    log.error("Email {} is given up after {} attempts and stays in email_message: {}", email.id(), failed, e.getMessage());
                } else {
                    log.warn("Email {} could not be sent (attempt {} of {}): {}", email.id(), failed, props.maxAttempts(), e.getMessage());
                }
                continue;
            }
            // Outside the try: a database failure here must not be recorded as the mail server refusing the email.
            emails.markSent(email, clock.instant());
            sent++;
        }
        return sent;
    }

    /** 1 minute after the first failure, doubling, at most an hour. */
    public Duration retryAfter(int failedAttempts) {
        long minutes = 1L << Math.min(Math.max(failedAttempts - 1, 0), 6);
        return Duration.ofMinutes(Math.min(minutes, 60));
    }
}
