package lk.auradot.wms.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "none").subject("user-1");
    }

    @Test
    void mapsRealmRolesToRoleAuthorities() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("wms-admin", "user", "user"))).build();

        assertThat(new KeycloakRealmRoleConverter().convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_wms-admin", "ROLE_user");
    }

    @Test
    void returnsNoRolesWhenClaimMissingOrMalformed() {
        assertThat(KeycloakRealmRoleConverter.realmRoles(token().claim("x", 1).build())).isEmpty();
        assertThat(KeycloakRealmRoleConverter.realmRoles(token().claim("realm_access", "oops").build())).isEmpty();
        assertThat(KeycloakRealmRoleConverter.realmRoles(
                token().claim("realm_access", Map.of("roles", List.of(1, "user"))).build()))
                .containsExactly("user");
    }
}
