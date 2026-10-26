package lk.auradot.wms.identity.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "wms.identity.source", havingValue = "AURADOCS")
public class AuraDocsClientConfig {

    @Bean
    AuraDocsDirectoryClient auraDocsDirectoryClient(AuraDocsProperties properties, KeycloakAdminClient keycloak) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());
        return new AuraDocsDirectoryClient(RestClient.builder().requestFactory(requestFactory), properties, keycloak::token);
    }
}
