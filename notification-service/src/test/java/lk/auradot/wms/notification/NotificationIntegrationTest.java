package lk.auradot.wms.notification;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** MySQL from Testcontainers; the RabbitMQ listener and the email scheduler are off (tests call them directly). */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "wms.notification.email.dispatch-enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestNotificationConfiguration.class})
public @interface NotificationIntegrationTest {
}
