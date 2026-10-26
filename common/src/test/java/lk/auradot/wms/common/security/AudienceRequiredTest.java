package lk.auradot.wms.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudienceRequiredTest {

    @Test
    void serviceWithoutConfiguredAudienceRefusesToStart() {
        // Without an audience check any token of the realm (e.g. another client's) would be accepted.
        var app = new SpringApplicationBuilder(CommonTestApplication.class).properties(
                "server.port=0",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/test");

        assertThatThrownBy(app::run)
                .hasStackTraceContaining("spring.security.oauth2.resourceserver.jwt.audiences");
    }
}
