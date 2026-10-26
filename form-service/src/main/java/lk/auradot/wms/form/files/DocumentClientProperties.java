package lk.auradot.wms.form.files;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("wms.form.documents")
public record DocumentClientProperties(URI baseUrl, URI tokenUrl, String clientId, String clientSecret,
                                       @DefaultValue("2s") Duration connectTimeout,
                                       @DefaultValue("5s") Duration readTimeout) {
}
