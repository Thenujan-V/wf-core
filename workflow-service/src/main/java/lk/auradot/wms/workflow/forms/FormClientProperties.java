package lk.auradot.wms.workflow.forms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("wms.workflow.forms")
public record FormClientProperties(URI baseUrl, @DefaultValue("2s") Duration connectTimeout,
                                   @DefaultValue("5s") Duration readTimeout) {
}
