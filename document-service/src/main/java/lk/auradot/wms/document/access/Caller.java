package lk.auradot.wms.document.access;

import java.util.Set;

/** Who is asking: user id, realm roles, identity-service group ids, and their bearer token for onward calls. */
public record Caller(String userId, Set<String> roles, Set<String> groupIds, String token) {

    /** These roles may do anything in the library (spec §7). */
    public static final Set<String> ADMIN_ROLES = Set.of("wms-admin", "document-admin");

    public boolean isAdmin() {
        return roles.stream().anyMatch(ADMIN_ROLES::contains);
    }

    public boolean matches(AclEntry entry) {
        return switch (entry.principalType()) {
            case USER -> entry.principalId().equals(userId);
            case GROUP -> groupIds.contains(entry.principalId());
            case ROLE -> roles.contains(entry.principalId());
        };
    }
}
