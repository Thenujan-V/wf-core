package lk.auradot.wms.identity;

import lk.auradot.wms.common.security.KeycloakRealmRoleConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/** Builds Keycloak-shaped test JWTs; given_name is the capitalised username, family_name is "Test". */
public final class TestJwt {

    private TestJwt() {
    }

    public static JwtRequestPostProcessor user(String sub, String username, String... roles) {
        String givenName = Character.toUpperCase(username.charAt(0)) + username.substring(1);
        return jwt()
                .jwt(j -> j.subject(sub)
                        .claim("preferred_username", username)
                        .claim("email", username + "@example.com")
                        .claim("given_name", givenName)
                        .claim("family_name", "Test")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
