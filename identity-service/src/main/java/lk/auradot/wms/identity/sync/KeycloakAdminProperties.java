package lk.auradot.wms.identity.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("wms.identity.keycloak")
public record KeycloakAdminProperties(URI baseUrl, String realm, String clientId, String clientSecret,
                                      @DefaultValue("100") int pageSize,
                                      @DefaultValue("5s") Duration connectTimeout,
                                      @DefaultValue("30s") Duration readTimeout) {
}
