package lk.auradot.wms.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("wms.outbox")
public record OutboxProperties(@DefaultValue("true") boolean enabled, @DefaultValue("PT1S") Duration pollInterval,
                               @DefaultValue("100") int batchSize) {
}
