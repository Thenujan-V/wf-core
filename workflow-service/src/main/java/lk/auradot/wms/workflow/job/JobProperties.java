package lk.auradot.wms.workflow.job;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("wms.workflow.jobs")
public record JobProperties(@DefaultValue("true") boolean enabled, @DefaultValue("PT1S") Duration pollInterval,
                            @DefaultValue("20") int batchSize, @DefaultValue("PT5M") Duration lease) {
}
