package lk.auradot.wms.workflow.directory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Where AuraDOCS serves its directory to the workflow service ({@code /integration/v1/directory} on API-Service).
 * The service-account credentials are the {@code wms.workflow.identity} ones.
 */
@ConfigurationProperties("wms.workflow.directory.auradocs")
public record AuraDocsDirectoryProperties(URI baseUrl,
                                          @DefaultValue("2s") Duration connectTimeout,
                                          @DefaultValue("5s") Duration readTimeout,
                                          @DefaultValue("60s") Duration cacheTtl) {
}
