package lk.auradot.wms.workflow.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("wms.workflow.identity")
public record IdentityClientProperties(URI baseUrl, URI tokenUrl, String clientId, String clientSecret,
                                       @DefaultValue("2s") Duration connectTimeout,
                                       @DefaultValue("5s") Duration readTimeout) {
}
