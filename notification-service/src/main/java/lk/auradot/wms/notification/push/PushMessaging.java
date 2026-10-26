package lk.auradot.wms.notification.push;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The instance that stores a notification is not necessarily the one the person's browser is connected to, so every
 * instance hears about every push and delivers the ones for the sessions it holds.
 */
@Configuration(proxyBeanMethods = false)
public class PushMessaging {

    public static final String EXCHANGE = "wms.notifications.push";

    @Bean
    FanoutExchange pushExchange() {
        return new FanoutExchange(EXCHANGE, true, false);
    }

    /** This instance's own queue: it goes away with the instance, and what was sent meanwhile is not kept. */
    @Bean
    Queue pushQueue() {
        return new AnonymousQueue();
    }

    @Bean
    Binding pushBinding(@Qualifier("pushQueue") Queue pushQueue, FanoutExchange pushExchange) {
        return BindingBuilder.bind(pushQueue).to(pushExchange);
    }
}
