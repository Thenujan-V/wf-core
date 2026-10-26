package lk.auradot.wms.document.requests;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class WorkflowConfig {

    @Bean
    InstanceAccessClient instanceAccessClient(WorkflowClientProperties props, Clock clock) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(props.readTimeout());
        return new HttpInstanceAccessClient(RestClient.builder().requestFactory(requestFactory), props, clock);
    }
}
