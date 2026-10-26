package lk.auradot.wms.document.access;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("wms.document.identity")
public record IdentityClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout, Duration cacheTtl) {
}
