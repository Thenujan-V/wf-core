package lk.auradot.wms.audit.entry;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.ImmediateRequeueMessageRecoverer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuditMessaging {

    public static final String QUEUE = "audit-service.events";
    public static final String FAILED_QUEUE = "audit-service.events.failed";

    @Bean
    Queue auditQueue() {
        return new Queue(QUEUE, true);
    }

    /** Events that can never be stored (see AuditListener) wait here for someone to look at them. */
    @Bean
    Queue auditFailedQueue() {
        return new Queue(FAILED_QUEUE, true);
    }

    /**
     * An audit trail may be late but must not lose events: when the retries are used up (the database is down, say),
     * the message goes back on the queue and is tried again, for as long as it takes.
     */
    @Bean
    MessageRecoverer auditRecoverer() {
        return new ImmediateRequeueMessageRecoverer();
    }

    /** Spec §8 "consumes all events": every routing key on the exchange. */
    @Bean
    Binding auditBinding(Queue auditQueue, TopicExchange wmsEvents) {
        return BindingBuilder.bind(auditQueue).to(wmsEvents).with("#");
    }
}
