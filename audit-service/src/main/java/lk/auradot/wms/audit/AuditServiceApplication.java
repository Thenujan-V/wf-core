package lk.auradot.wms.audit;

import lk.auradot.wms.common.EnableWmsService;
import lk.auradot.wms.common.messaging.WmsEventsConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Clock;

/** Spec §8: every domain event becomes an append-only audit entry that administrators can search. */
@SpringBootApplication
@EnableWmsService
@Import(WmsEventsConfiguration.class)
public class AuditServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
