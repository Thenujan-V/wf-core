package lk.auradot.wms.workflow.directory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;

/** {@code wms.workflow.directory.provider} picks the org chart: identity-service (default) or AuraDOCS. */
@Configuration(proxyBeanMethods = false)
public class DirectoryConfig {

    /** Timeouts keep a slow identity-service from holding workflow transactions (spec §2.2). */
    @Bean
    @ConditionalOnProperty(name = "wms.workflow.directory.provider", havingValue = "identity", matchIfMissing = true)
    IdentityDirectory identityDirectory(IdentityClientProperties props, Clock clock) {
        return new IdentityDirectory(client(props.connectTimeout(), props.readTimeout()), props, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "wms.workflow.directory.provider", havingValue = "auradocs")
    AuraDocsDirectory auraDocsDirectory(AuraDocsDirectoryProperties props, IdentityClientProperties credentials,
                                        Clock clock) {
        return new AuraDocsDirectory(client(props.connectTimeout(), props.readTimeout()), props, credentials, clock);
    }

    private static RestClient.Builder client(Duration connectTimeout, Duration readTimeout) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
