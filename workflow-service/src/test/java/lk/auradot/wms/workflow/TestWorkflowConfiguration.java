package lk.auradot.wms.workflow;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestWorkflowConfiguration {

    @Bean
    @Primary
    StubDirectory stubDirectory() {
        StubDirectory directory = new StubDirectory();
        directory.reset();
        return directory;
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    StubForms stubForms() {
        return new StubForms();
    }
}
