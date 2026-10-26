package lk.auradot.wms.identity.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The default source: users, realm roles, groups and memberships all from Keycloak. */
@Component
@ConditionalOnProperty(name = "wms.identity.source", havingValue = "KEYCLOAK", matchIfMissing = true)
public class KeycloakDirectorySource implements DirectorySource {

    static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    private final KeycloakAdminClient keycloak;

    public KeycloakDirectorySource(KeycloakAdminClient keycloak) {
        this.keycloak = keycloak;
    }

    @Override
    public DirectorySnapshot load() {
        List<KcUser> users = people(keycloak);
        Map<String, List<String>> roles = realmRoles(keycloak, users);
        List<KcGroup> groups = keycloak.listGroups();
        Map<String, List<String>> members = new LinkedHashMap<>();
        for (KcGroup group : groups) {
            members.put(group.id(), keycloak.groupMemberIds(group.id()));
        }
        return new DirectorySnapshot(users, roles, groups, members);
    }

    /** Every Keycloak user except service accounts. */
    static List<KcUser> people(KeycloakAdminClient keycloak) {
        return keycloak.listUsers().stream()
                .filter(u -> !u.username().startsWith(SERVICE_ACCOUNT_PREFIX))
                .toList();
    }

    static Map<String, List<String>> realmRoles(KeycloakAdminClient keycloak, List<KcUser> users) {
        Map<String, List<String>> roles = new LinkedHashMap<>();
        for (KcUser user : users) {
            roles.put(user.id(), keycloak.effectiveRealmRoles(user.id()));
        }
        return roles;
    }
}
