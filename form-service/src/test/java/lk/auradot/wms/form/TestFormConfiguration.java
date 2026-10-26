package lk.auradot.wms.form;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestFormConfiguration {

    @Bean
    @Primary
    FakeFiles fakeFiles() {
        return new FakeFiles();
    }
}
