package lk.auradot.wms.identity.sync;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DirectorySnapshotLoader {

    static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    private final KeycloakAdminClient keycloak;

    public DirectorySnapshotLoader(KeycloakAdminClient keycloak) {
        this.keycloak = keycloak;
    }

    public DirectorySnapshot load() {
        List<KcUser> users = keycloak.listUsers().stream()
                .filter(u -> !u.username().startsWith(SERVICE_ACCOUNT_PREFIX))
                .toList();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        for (KcUser user : users) {
            roles.put(user.id(), keycloak.effectiveRealmRoles(user.id()));
        }
        List<KcGroup> groups = keycloak.listGroups();
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (KcGroup group : groups) {
            members.put(group.id(), keycloak.groupMemberIds(group.id()));
        }
        return new DirectorySnapshot(users, roles, groups, members);
    }
}
