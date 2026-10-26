package lk.auradot.wms.notification.email;

import jakarta.mail.SendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP through Spring Mail: Mailpit in development, Amazon SES's SMTP endpoint in production (spec §8). */
@Component
public class SmtpMailer implements Mailer {

    private final JavaMailSender sender;
    private final EmailProperties props;

    public SmtpMailer(JavaMailSender sender, EmailProperties props) {
        this.sender = sender;
        this.props = props;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(props.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
        } catch (MailAuthenticationException e) {
            throw new MailServerUnavailableException("The mail server did not accept our sign-in: " + e.getMessage(), e);
        } catch (MailSendException e) {
            // Only a server that answered and refused this message makes it the message's failure; a connection that
            // could not be made, or that broke or timed out half-way, is an outage.
            boolean refused = e.getCause() == null
                    && e.getFailedMessages().values().stream().anyMatch(SendFailedException.class::isInstance);
            if (refused) {
                throw e;
            }
            throw new MailServerUnavailableException("The mail server could not be reached: " + e.getMessage(), e);
        }
    }
}
