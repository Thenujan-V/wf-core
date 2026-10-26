package lk.auradot.wms.notification;

import lk.auradot.wms.notification.email.MailServerUnavailableException;
import lk.auradot.wms.notification.email.Mailer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Stands in for the mail server: keeps what it was given, cannot be reached while "down", or refuses every email. */
public class FakeMailer implements Mailer {

    public record Sent(String to, String subject, String body) {
    }

    private final List<Sent> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private volatile boolean down;
    private volatile boolean refusing;

    public void reset() {
        sent.clear();
        attempts.set(0);
        down = false;
        refusing = false;
    }

    /** The mail server cannot be reached at all. */
    public void down(boolean down) {
        this.down = down;
    }

    /** The mail server answers, and refuses each email. */
    public void refuse(boolean refusing) {
        this.refusing = refusing;
    }

    public List<Sent> sent() {
        return sent;
    }

    /** How many times the mail server was asked to send something. */
    public int attempts() {
        return attempts.get();
    }

    @Override
    public void send(String to, String subject, String body) {
        attempts.incrementAndGet();
        if (down) {
            throw new MailServerUnavailableException("the mail server is down (test)", null);
        }
        if (refusing) {
            throw new IllegalStateException("the mail server refused the email (test)");
        }
        sent.add(new Sent(to, subject, body));
    }
}
