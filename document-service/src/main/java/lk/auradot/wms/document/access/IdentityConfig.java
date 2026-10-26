package lk.auradot.wms.document.access;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class IdentityConfig {

    /** Timeouts keep a slow identity-service from holding request threads (spec §2.2). */
    @Bean
    GroupLookup identityGroupLookup(IdentityClientProperties props, Clock clock) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(props.readTimeout());
        return new IdentityGroupLookup(RestClient.builder().requestFactory(requestFactory), props, clock);
    }
}
