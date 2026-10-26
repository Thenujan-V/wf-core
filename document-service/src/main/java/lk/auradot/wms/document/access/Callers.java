package lk.auradot.wms.document.access;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class Callers {

    private final GroupLookup groups;

    public Callers(GroupLookup groups) {
        this.groups = groups;
    }

    public Caller of(Jwt jwt) {
        Set<String> roles = Set.copyOf(realmRoles(jwt));
        return new Caller(jwt.getSubject(), roles, groups.groupIds(jwt.getSubject(), jwt.getTokenValue()), jwt.getTokenValue());
    }

    private static List<String> realmRoles(Jwt jwt) {
        if (jwt.getClaim("realm_access") instanceof Map<?, ?> access && access.get("roles") instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }
}
