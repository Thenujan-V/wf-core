package lk.auradot.wms.notification.email;

/** Hands one plain-text email to the mail server; throws when the server refuses it or cannot be reached. */
public interface Mailer {

    void send(String to, String subject, String body);
}
