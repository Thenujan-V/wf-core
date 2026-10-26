package lk.auradot.wms.notification.email;

import lk.auradot.wms.notification.FakeMailer;
import lk.auradot.wms.notification.MutableClock;
import lk.auradot.wms.notification.NotificationIntegrationTest;
import lk.auradot.wms.notification.TestDb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Emails wait in their queue until the mail server takes them. */
@NotificationIntegrationTest
class EmailDispatcherTest {

    @Autowired EmailDispatcher dispatcher;
    @Autowired EmailStore emails;
    @Autowired FakeMailer mailer;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    @AfterEach
    void reset() {
        clock.set(MutableClock.DEFAULT);
        TestDb.clear(jdbc);
        mailer.reset();
    }

    long unsent() {
        return jdbc.sql("SELECT COUNT(*) FROM email_message WHERE sent_at IS NULL").query(Long.class).single();
    }

    @Test
    void sendsWhatIsQueuedOnce() {
        emails.queue("bob@example.com", "New task: Review", "You have a new task.", clock.instant());
        emails.queue("carol@example.com", "Your request EXP-7 is approved", "It has finished.", clock.instant());

        assertThat(dispatcher.sendPending()).isEqualTo(2);
        assertThat(dispatcher.sendPending()).isZero();

        assertThat(mailer.sent()).extracting(FakeMailer.Sent::to).containsExactly("bob@example.com", "carol@example.com");
        assertThat(mailer.sent().getFirst()).isEqualTo(new FakeMailer.Sent("bob@example.com", "New task: Review", "You have a new task."));
        assertThat(unsent()).isZero();
    }

    @Test
    void anEmailTheMailServerRefusedWaitsAndIsTriedAgain() {
        emails.queue("bob@example.com", "New task: Review", "You have a new task.", clock.instant());
        mailer.refuse(true);

        assertThat(dispatcher.sendPending()).isZero();
        assertThat(jdbc.sql("SELECT attempts FROM email_message").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT last_error FROM email_message").query(String.class).single()).contains("refused");

        mailer.refuse(false);
        assertThat(dispatcher.sendPending()).isZero(); // not yet: it waits a minute before the next try
        clock.advance(Duration.ofSeconds(61));
        assertThat(dispatcher.sendPending()).isEqualTo(1);
        assertThat(unsent()).isZero();
    }

    @Test
    void pausesGrowAndAfterTheLastAttemptTheEmailIsKeptWithItsError() {
        assertThat(dispatcher.retryAfter(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(dispatcher.retryAfter(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(dispatcher.retryAfter(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(dispatcher.retryAfter(9)).isEqualTo(Duration.ofHours(1));

        emails.queue("bob@example.com", "New task: Review", "You have a new task.", clock.instant());
        mailer.refuse(true);
        for (int attempt = 1; attempt <= 12; attempt++) {
            dispatcher.sendPending();
            clock.advance(Duration.ofHours(2));
        }
        mailer.refuse(false);

        assertThat(dispatcher.sendPending()).isZero();
        assertThat(jdbc.sql("SELECT attempts FROM email_message").query(Integer.class).single()).isEqualTo(5);
        assertThat(unsent()).isEqualTo(1);
        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    void anEmailBeingSentByOneInstanceIsNotTakenByAnother() {
        emails.queue("bob@example.com", "New task: Review", "You have a new task.", clock.instant());

        assertThat(emails.claim(10, 5, clock.instant(), Duration.ofMinutes(2))).hasSize(1);
        assertThat(emails.claim(10, 5, clock.instant(), Duration.ofMinutes(2))).isEmpty();
        clock.advance(Duration.ofMinutes(3)); // the first instance died before it finished
        assertThat(emails.claim(10, 5, clock.instant(), Duration.ofMinutes(2))).hasSize(1);
    }

    @Test
    void aMailServerOutageHoweverLongLosesNoEmail() {
        emails.queue("bob@example.com", "New task: Review", "You have a new task.", clock.instant());
        mailer.down(true);
        for (int round = 1; round <= 12; round++) {
            assertThat(dispatcher.sendPending()).isZero();
            clock.advance(Duration.ofHours(2));
        }
        assertThat(jdbc.sql("SELECT attempts FROM email_message").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT last_error FROM email_message").query(String.class).single()).contains("down");

        mailer.down(false);
        assertThat(dispatcher.sendPending()).isEqualTo(1);
        assertThat(unsent()).isZero();
    }

    @Test
    void whileTheMailServerCannotBeReachedTheRestOfTheBatchIsNotTried() {
        for (String to : List.of("alice@example.com", "bob@example.com", "carol@example.com")) {
            emails.queue(to, "New task: Review", "You have a new task.", clock.instant());
        }
        mailer.down(true);

        assertThat(dispatcher.sendPending()).isZero();
        assertThat(mailer.attempts()).isEqualTo(1);

        mailer.down(false);
        clock.advance(Duration.ofSeconds(61));
        assertThat(dispatcher.sendPending()).isEqualTo(3);
    }

    @Test
    void anInstanceWhoseHoldRanOutNeitherSendsNorMarksTheEmail() {
        emails.queue("bob@example.com", "New task: Review", "You have a new task.", clock.instant());
        QueuedEmail stale = emails.claim(10, 5, clock.instant(), Duration.ofMinutes(2)).getFirst();
        clock.advance(Duration.ofMinutes(3)); // the first instance is stuck; its hold has run out

        assertThat(dispatcher.sendPending()).isEqualTo(1); // another instance takes the email over and sends it

        assertThat(emails.renew(stale, clock.instant().plus(Duration.ofMinutes(2)))).isEmpty();
        assertThat(emails.markFailed(stale, "late", clock.instant())).isFalse();
        assertThat(emails.markSent(stale, clock.instant())).isFalse();
        assertThat(jdbc.sql("SELECT attempts FROM email_message").query(Integer.class).single()).isZero();
        assertThat(mailer.sent()).hasSize(1);
        assertThat(unsent()).isZero();
    }
}
