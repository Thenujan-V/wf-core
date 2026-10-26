package lk.auradot.wms.document.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** document-service's own Keycloak client, for calls no person is behind. */
@ConfigurationProperties("wms.document.service-account")
public record ServiceAccountProperties(URI tokenUrl, String clientId, String clientSecret) {
}
