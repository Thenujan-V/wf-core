package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.workflow.directory.IdentityClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class FormsConfig {

    /** Timeouts keep a slow form-service from holding request threads (spec §2.2). */
    @Bean
    FormServiceClient formServiceClient(FormClientProperties props, IdentityClientProperties credentials, Clock clock) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(props.readTimeout());
        return new FormServiceClient(RestClient.builder().requestFactory(requestFactory), props, credentials, clock);
    }
}
