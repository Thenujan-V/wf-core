package lk.auradot.wms.notification.email;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** Emails wait in email_message until the mail server takes them. */
@Repository
public class EmailStore {

    static final int SUBJECT_MAX = 255;
    static final int ERROR_MAX = 500;

    private final JdbcClient jdbc;

    public EmailStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void queue(String to, String subject, String body, Instant now) {
        jdbc.sql("INSERT INTO email_message (to_address, subject, body, created_at) VALUES (:to, :subject, :body, :at)")
                .param("to", to).param("subject", subject.length() <= SUBJECT_MAX ? subject : subject.substring(0, SUBJECT_MAX))
                .param("body", body).param("at", utc(now)).update();
    }

    /**
     * Takes up to {@code limit} unsent emails that are due, and holds them for this instance for {@code hold}: another
     * instance skips them meanwhile, and takes them over if this one dies before it finishes.
     */
    @Transactional
    public List<QueuedEmail> claim(int limit, int maxAttempts, Instant now, Duration hold) {
        Instant until = stored(now.plus(hold));
        List<QueuedEmail> due = jdbc.sql("""
                        SELECT id, to_address, subject, body, attempts FROM email_message
                        WHERE sent_at IS NULL AND attempts < :max AND (claimed_until IS NULL OR claimed_until < :now)
                        ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("max", maxAttempts).param("now", utc(now)).param("limit", limit)
                .query((rs, row) -> new QueuedEmail(rs.getLong("id"), rs.getString("to_address"), rs.getString("subject"),
                        rs.getString("body"), rs.getInt("attempts"), until))
                .list();
        for (QueuedEmail email : due) {
            jdbc.sql("UPDATE email_message SET claimed_until = :until WHERE id = :id")
                    .param("until", utc(until)).param("id", email.id()).update();
        }
        return due;
    }

    /**
     * Extends the hold just before the email is sent. Empty when the hold ran out and another instance has taken the
     * email over: then it is theirs to send, not ours.
     */
    public Optional<QueuedEmail> renew(QueuedEmail email, Instant until) {
        Instant next = stored(until);
        int updated = jdbc.sql("""
                        UPDATE email_message SET claimed_until = :next
                        WHERE id = :id AND sent_at IS NULL AND claimed_until = :mine""")
                .param("next", utc(next)).param("id", email.id()).param("mine", utc(email.claimedUntil())).update();
        return updated == 0 ? Optional.empty()
                : Optional.of(new QueuedEmail(email.id(), email.to(), email.subject(), email.body(), email.attempts(), next));
    }

    /** False (and nothing changes) when the email is no longer held by the caller; the same goes for the two below. */
    public boolean markSent(QueuedEmail email, Instant now) {
        return jdbc.sql("""
                        UPDATE email_message SET sent_at = :at, claimed_until = NULL, last_error = NULL
                        WHERE id = :id AND sent_at IS NULL AND claimed_until = :mine""")
                .param("at", utc(now)).param("id", email.id()).param("mine", utc(email.claimedUntil())).update() > 0;
    }

    /** The mail server refused this email: counts the attempt and leaves the email alone until {@code retryAt}. */
    public boolean markFailed(QueuedEmail email, String error, Instant retryAt) {
        return jdbc.sql("""
                        UPDATE email_message SET attempts = attempts + 1, last_error = :error, claimed_until = :retry
                        WHERE id = :id AND sent_at IS NULL AND claimed_until = :mine""")
                .param("error", cut(error)).param("retry", utc(retryAt)).param("id", email.id())
                .param("mine", utc(email.claimedUntil())).update() > 0;
    }

    /** The mail server could not be reached: the email waits until {@code retryAt} without the attempt counting. */
    public boolean defer(QueuedEmail email, String error, Instant retryAt) {
        return jdbc.sql("""
                        UPDATE email_message SET last_error = :error, claimed_until = :retry
                        WHERE id = :id AND sent_at IS NULL AND claimed_until = :mine""")
                .param("error", cut(error)).param("retry", utc(retryAt)).param("id", email.id())
                .param("mine", utc(email.claimedUntil())).update() > 0;
    }

    private static String cut(String error) {
        String text = error == null ? "unknown error" : error;
        return text.length() <= ERROR_MAX ? text : text.substring(0, ERROR_MAX);
    }

    /** DATETIME(6) keeps microseconds; a hold is compared with what was stored, so it is cut the same way. */
    private static Instant stored(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(stored(instant), ZoneOffset.UTC);
    }
}
