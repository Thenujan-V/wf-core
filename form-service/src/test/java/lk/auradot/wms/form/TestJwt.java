package lk.auradot.wms.form;

import lk.auradot.wms.common.security.KeycloakRealmRoleConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TestJwt {

    private TestJwt() {
    }

    public static JwtRequestPostProcessor user(String sub, String... roles) {
        return jwt()
                .jwt(j -> j.subject(sub).claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
