package lk.auradot.wms.common.messaging;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/** Publishing and consuming WMS events (spec §2.2): the exchange, the transactional outbox, idempotent consumers. */
@Configuration(proxyBeanMethods = false)
@Import(WmsEventsConfiguration.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class WmsMessagingConfiguration {

    @Bean
    Outbox outbox(JdbcClient jdbc, JsonMapper mapper) {
        return new Outbox(jdbc, mapper);
    }

    @Bean
    OutboxRelay outboxRelay(Outbox outbox, RabbitTemplate rabbit, TransactionTemplate tx, Clock clock, OutboxProperties props) {
        return new OutboxRelay(outbox, rabbit, tx, clock, props);
    }

    @Bean
    @ConditionalOnProperty(name = "wms.outbox.enabled", havingValue = "true", matchIfMissing = true)
    OutboxScheduler outboxScheduler(OutboxRelay relay) {
        return new OutboxScheduler(relay);
    }

    @Bean
    ProcessedMessages processedMessages(JdbcClient jdbc) {
        return new ProcessedMessages(jdbc);
    }
}
