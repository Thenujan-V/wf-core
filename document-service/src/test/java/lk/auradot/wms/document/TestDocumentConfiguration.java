package lk.auradot.wms.document;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestDocumentConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeGroups fakeGroups() {
        return new FakeGroups();
    }

    @Bean
    @Primary
    FakeForms fakeForms() {
        return new FakeForms();
    }

    @Bean
    @Primary
    FakeInstances fakeInstances() {
        return new FakeInstances();
    }

    @Bean
    @Primary
    FakeOutcomes fakeOutcomes() {
        return new FakeOutcomes();
    }

    @Bean
    @Primary
    FakePeople fakePeople() {
        return new FakePeople();
    }
}
