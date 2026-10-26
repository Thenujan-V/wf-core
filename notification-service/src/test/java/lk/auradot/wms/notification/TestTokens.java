package lk.auradot.wms.notification;

import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The tests' token check, for tests that connect for real (MockMvc tests do not come here): "test:bob" is bob's token,
 * valid for five minutes of the test clock; everything else is refused.
 */
public class TestTokens implements JwtDecoder {

    public static final Duration LIFETIME = Duration.ofMinutes(5);
    private static final String PREFIX = "test:";

    private final Clock clock;

    public TestTokens(Clock clock) {
        this.clock = clock;
    }

    public static String of(String userId) {
        return PREFIX + userId;
    }

    @Override
    public Jwt decode(String token) {
        if (token == null || !token.startsWith(PREFIX) || token.length() == PREFIX.length()) {
            throw new BadJwtException("not a test token");
        }
        Instant now = clock.instant();
        return Jwt.withTokenValue(token).header("alg", "none").subject(token.substring(PREFIX.length()))
                .issuedAt(now).expiresAt(now.plus(LIFETIME)).build();
    }
}
