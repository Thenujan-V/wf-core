package lk.auradot.wms.identity.sync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeycloakDirectorySourceTest {

    @Test
    void loadsUsersRolesGroupsAndMembersSkippingServiceAccounts() {
        KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
        when(keycloak.listUsers()).thenReturn(List.of(
                new KcUser("1", "alice", null, null, null, true, Map.of()),
                new KcUser("sa", "service-account-wms-identity-sync", null, null, null, true, Map.of())));
        when(keycloak.effectiveRealmRoles("1")).thenReturn(List.of("wms-admin", "user"));
        when(keycloak.listGroups()).thenReturn(List.of(new KcGroup("g1", "Management", "/Management", 0)));
        when(keycloak.groupMemberIds("g1")).thenReturn(List.of("1", "sa"));

        DirectorySnapshot snapshot = new KeycloakDirectorySource(keycloak).load();

        assertThat(snapshot.users()).extracting(KcUser::id).containsExactly("1");
        assertThat(snapshot.rolesByUserId()).containsOnlyKeys("1");
        assertThat(snapshot.rolesByUserId().get("1")).containsExactly("wms-admin", "user");
        assertThat(snapshot.memberIdsByGroupId().get("g1")).containsExactly("1", "sa");
    }
}
