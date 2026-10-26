package lk.auradot.wms.notification.events;

import lk.auradot.wms.notification.plan.NotificationPlanner;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.retry.ImmediateRequeueMessageRecoverer;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class NotificationMessaging {

    public static final String QUEUE = "notification-service.events";

    @Bean
    Queue notificationQueue() {
        return new Queue(QUEUE, true);
    }

    /** One binding per event the planner knows; everything else never reaches this service. */
    @Bean
    Declarables notificationBindings(@Qualifier("notificationQueue") Queue notificationQueue, TopicExchange wmsEvents) {
        List<Declarable> bindings = NotificationPlanner.ROUTING_KEYS.stream()
                .map(key -> (Declarable) BindingBuilder.bind(notificationQueue).to(wmsEvents).with(key)).toList();
        return new Declarables(bindings);
    }

    /**
     * Nobody should miss a notification because identity-service or the database was down for a while: when the
     * retries are used up, the event goes back on the queue and is tried again.
     */
    @Bean
    MessageRecoverer notificationRecoverer() {
        return new ImmediateRequeueMessageRecoverer();
    }
}
