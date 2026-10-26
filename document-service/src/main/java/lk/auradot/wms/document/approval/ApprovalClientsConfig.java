package lk.auradot.wms.document.approval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ApprovalClientsConfig {

    /** Short timeouts: these calls run in a listener and a schedule, and are tried again anyway. */
    private static RestClient.Builder builder() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    RequestOutcomes requestOutcomes(@Value("${wms.document.workflow.base-url}") URI workflow, ServiceAccountProperties account, Clock clock) {
        return new HttpRequestOutcomes(builder(), workflow, account, clock);
    }

    @Bean
    People people(@Value("${wms.document.identity.base-url}") URI identity, ServiceAccountProperties account, Clock clock) {
        return new HttpPeople(builder(), identity, account, clock);
    }
}
