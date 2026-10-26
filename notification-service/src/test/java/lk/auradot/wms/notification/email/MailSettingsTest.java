package lk.auradot.wms.notification.email;

import lk.auradot.wms.notification.NotificationIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/** Jakarta Mail waits forever by default; a mail server that stops answering must not stop all sending. */
@NotificationIntegrationTest
class MailSettingsTest {

    @Autowired JavaMailSenderImpl sender;

    @Test
    void talkingToTheMailServerIsBoundedInTime() {
        for (String timeout : new String[] {"mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout"}) {
            assertThat(sender.getJavaMailProperties().getProperty(timeout)).as(timeout).isNotNull()
                    .satisfies(value -> assertThat(Integer.parseInt(value)).isBetween(1000, 30000));
        }
    }
}
