package lk.auradot.wms.document;

import lk.auradot.wms.common.security.KeycloakRealmRoleConverter;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

public final class TestJwt {

    private TestJwt() {
    }

    /** Every realm user also holds "user" (the realm's default roles). */
    public static JwtRequestPostProcessor user(String sub, String... roles) {
        List<String> all = new java.util.ArrayList<>(List.of(roles));
        all.add("user");
        return jwt()
                .jwt(j -> j.subject(sub).tokenValue("token-" + sub).claim("preferred_username", sub)
                        .claim("realm_access", Map.of("roles", all)))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
