package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.workflow.StubDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {

    private static JwtAuthenticationToken token(String sub, String username) {
        Jwt.Builder jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub)
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60));
        if (username != null) {
            jwt.claim("preferred_username", username);
        }
        return new JwtAuthenticationToken(jwt.build());
    }

    @Test
    void theSubjectIsTheUserIdByDefault() {
        UserContext users = new UserContext(new StubDirectory(), new UserIdProperties("sub", false));

        assertThat(users.userId(token("5f1c-uuid", "nimal.perera@acme.lk"))).isEqualTo("5f1c-uuid");
    }

    @Test
    void anEmbeddingSystemCanKeyUsersByUsernameWithoutTheEmailDomain() {
        UserContext users = new UserContext(new StubDirectory(), new UserIdProperties("preferred_username", true));

        assertThat(users.userId(token("5f1c-uuid", "nimal.perera@acme.lk"))).isEqualTo("nimal.perera");
        assertThat(users.userId(token("5f1c-uuid", "kamala"))).isEqualTo("kamala");
    }

    @Test
    void aTokenWithoutTheClaimFallsBackToTheSubject() {
        UserContext users = new UserContext(new StubDirectory(), new UserIdProperties("preferred_username", true));

        assertThat(users.userId(token("service-uuid", null))).isEqualTo("service-uuid");
    }

    @Test
    void theDomainIsKeptUnlessStrippingIsOn() {
        UserContext users = new UserContext(new StubDirectory(), new UserIdProperties("preferred_username", false));

        assertThat(users.userId(token("5f1c-uuid", "nimal.perera@acme.lk"))).isEqualTo("nimal.perera@acme.lk");
    }
}
