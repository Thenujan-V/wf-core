package lk.auradot.wms.notification;

import lk.auradot.wms.common.EnableWmsService;
import lk.auradot.wms.common.messaging.ProcessedMessages;
import lk.auradot.wms.common.messaging.WmsEventsConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Spec §8: turns domain events into in-app notifications and emails, honouring each person's preferences. */
@SpringBootApplication
@EnableWmsService
@EnableScheduling
@ConfigurationPropertiesScan
@Import(WmsEventsConfiguration.class)
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** This service only consumes events, so it takes the idempotent-consumer store without the outbox. */
    @Bean
    ProcessedMessages processedMessages(JdbcClient jdbc) {
        return new ProcessedMessages(jdbc);
    }
}
