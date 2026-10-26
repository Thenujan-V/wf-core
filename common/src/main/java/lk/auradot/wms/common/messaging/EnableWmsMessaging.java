package lk.auradot.wms.common.messaging;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Adds the shared event plumbing to a service that owns an {@code outbox} table: the exchange, the outbox with its
 * relay, and the processed-message store. The service needs the JDBC and AMQP starters and a {@code Clock} bean.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(WmsMessagingConfiguration.class)
public @interface EnableWmsMessaging {
}
