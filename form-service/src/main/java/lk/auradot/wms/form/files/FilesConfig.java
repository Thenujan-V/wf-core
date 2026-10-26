package lk.auradot.wms.form.files;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentClientProperties.class)
public class FilesConfig {

    /** Timeouts keep a slow document-service from holding submissions (spec §2.2). */
    @Bean
    FileVerifier fileVerifier(DocumentClientProperties props, Clock clock) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(props.readTimeout());
        return new DocumentServiceFiles(RestClient.builder().requestFactory(requestFactory), props, clock);
    }
}
