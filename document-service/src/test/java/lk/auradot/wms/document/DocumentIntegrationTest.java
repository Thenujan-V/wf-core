package lk.auradot.wms.document;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** MySQL and SeaweedFS from Testcontainers; the outbox relay, upload and staged-file purges, the approval settlement schedule and RabbitMQ listeners are off. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "wms.outbox.enabled=false",
        "wms.document.uploads.purge-enabled=false",
        "wms.document.text.backfill-enabled=false",
        "wms.document.staging.purge-enabled=false",
        "wms.document.approvals.settle-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestDocumentConfiguration.class})
public @interface DocumentIntegrationTest {
}
