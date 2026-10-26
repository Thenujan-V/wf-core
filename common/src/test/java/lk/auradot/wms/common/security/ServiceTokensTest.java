package lk.auradot.wms.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ServiceTokensTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-28T09:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aTokenIsReusedUntilShortlyBeforeItExpires() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(ExpectedCount.twice(), requestTo("http://kc/token"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("client_id=wms-form")))
                .andRespond(withSuccess("{\"access_token\":\"t\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        TestClock clock = new TestClock();
        ServiceTokens tokens = new ServiceTokens(builder.build(), URI.create("http://kc/token"), "wms-form", "secret", clock);

        assertThat(tokens.get()).isEqualTo("t");
        clock.now = clock.now.plus(Duration.ofSeconds(260));
        assertThat(tokens.get()).isEqualTo("t");
        clock.now = clock.now.plus(Duration.ofSeconds(20)); // within 30 s of expiry
        assertThat(tokens.get()).isEqualTo("t");
        server.verify();
    }
}
