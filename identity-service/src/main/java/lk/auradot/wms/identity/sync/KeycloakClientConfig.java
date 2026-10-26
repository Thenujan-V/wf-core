package lk.auradot.wms.identity.sync;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class KeycloakClientConfig {

    /** Timeouts keep a stalled Keycloak from blocking the single scheduler thread forever (spec §2.2). */
    @Bean
    KeycloakAdminClient keycloakAdminClient(KeycloakAdminProperties properties, Clock clock) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return new KeycloakAdminClient(RestClient.builder().requestFactory(requestFactory), properties, clock);
    }
}
