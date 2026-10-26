package lk.auradot.wms.document.requests;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("wms.document.workflow")
public record WorkflowClientProperties(URI baseUrl, Duration connectTimeout, Duration readTimeout, Duration cacheTtl) {
}
