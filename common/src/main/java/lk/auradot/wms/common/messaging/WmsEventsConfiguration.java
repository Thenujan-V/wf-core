package lk.auradot.wms.common.messaging;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The events exchange alone, for services that only consume. Publishers use {@link EnableWmsMessaging}. */
@Configuration(proxyBeanMethods = false)
public class WmsEventsConfiguration {

    @Bean
    TopicExchange wmsEvents() {
        return new TopicExchange(WmsEvents.EXCHANGE, true, false);
    }
}
