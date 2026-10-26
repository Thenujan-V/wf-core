package lk.auradot.wms.notification;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test doubles for what lies outside this service: the clock now; the directory and mail server in later tasks. */
@TestConfiguration(proxyBeanMethods = false)
public class TestNotificationConfiguration {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeDirectory fakeDirectory() {
        return new FakeDirectory();
    }

    @Bean
    @Primary
    FakeMailer fakeMailer() {
        return new FakeMailer();
    }

    @Bean
    @Primary
    RecordingLivePush recordingLivePush() {
        return new RecordingLivePush();
    }

    @Bean
    @Primary
    TestTokens testTokens(MutableClock clock) {
        return new TestTokens(clock);
    }
}
