package lk.auradot.wms.workflow.job;

import lk.auradot.wms.workflow.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;

@Configuration(proxyBeanMethods = false)
public class WebhookConfig {

    @Bean
    HostResolver hostResolver() {
        return InetAddress::getAllByName;
    }

    /** Timeouts, and no redirects: a redirect could lead past the SSRF guard. */
    @Bean
    WebhookSender webhookSender(WebhookProperties props, HostResolver resolver, Json json) {
        var requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(props.readTimeout());
        return new WebhookSender(RestClient.builder().requestFactory(requestFactory).build(), props, resolver, json);
    }

    @Bean
    ActionRunner actionRunner(WebhookSender webhooks) {
        return new ActionRunner(webhooks);
    }
}
