package lk.auradot.wms.workflow.directory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class DirectoryConfig {

    /** Timeouts keep a slow identity-service from holding workflow transactions (spec §2.2). */
    @Bean
    IdentityDirectory identityDirectory(IdentityClientProperties props, Clock clock) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(props.readTimeout());
        return new IdentityDirectory(RestClient.builder().requestFactory(requestFactory), props, clock);
    }
}
