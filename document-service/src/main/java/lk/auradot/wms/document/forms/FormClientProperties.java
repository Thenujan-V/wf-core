package lk.auradot.wms.document.forms;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("wms.document.forms")
public record FormClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout) {
}
