package lk.auradot.wms.notification.email;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmtpMailerTest {

    @Test
    void aMailServerThatCannotBeReachedIsAnOutageNotARefusedEmail() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("localhost");
        sender.setPort(closedPort);
        sender.getJavaMailProperties().setProperty("mail.smtp.connectiontimeout", "2000");
        SmtpMailer mailer = new SmtpMailer(sender, new EmailProperties("wms@localhost", false, Duration.ofSeconds(5), 5));

        assertThatThrownBy(() -> mailer.send("bob@example.com", "New task: Review", "You have a new task."))
                .isInstanceOf(MailServerUnavailableException.class);
    }
}
