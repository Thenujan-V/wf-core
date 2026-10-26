package lk.auradot.wms.identity.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuraDocsDirectorySourceTest {

    private KeycloakAdminClient keycloak;
    private AuraDocsDirectoryClient auraDocs;
    private AuraDocsDirectorySource source;

    @BeforeEach
    void setUp() {
        keycloak = mock(KeycloakAdminClient.class);
        auraDocs = mock(AuraDocsDirectoryClient.class);
        var props = new AuraDocsProperties(URI.create("http://docs"), "/integration/wms/directory",
                Map.of("ROLE_ADMIN", List.of("wms-admin"), "role_designer", List.of("process-designer", "document-admin")),
                Duration.ofSeconds(5), Duration.ofSeconds(30));
        source = new AuraDocsDirectorySource(keycloak, auraDocs, props);
    }

    private static AuraDocsDirectory.User adUser(String id, String email, Boolean active, List<String> groups,
                                                 List<String> departments, List<String> roles) {
        return new AuraDocsDirectory.User(id, email, id, active, groups, departments, roles);
    }

    @Test
    void mapsGroupsAndDepartmentsAndJoinsUsersByEmail() {
        when(keycloak.listUsers()).thenReturn(List.of(
                new KcUser("kc-1", "alice", "Alice@Acme.com", "Alice", "Perera", true,
                        Map.of("department", List.of("Old"), "position", List.of("Clerk"))),
                new KcUser("kc-2", "bob", "bob@acme.com", null, null, true, Map.of("department", List.of("Kept"))),
                new KcUser("kc-3", "carol", null, null, null, true, Map.of()),
                new KcUser("sa", "service-account-wms-identity-sync", "sa@acme.com", null, null, true, Map.of())));
        when(keycloak.effectiveRealmRoles("kc-1")).thenReturn(List.of("user"));
        when(keycloak.effectiveRealmRoles("kc-2")).thenReturn(List.of("user", "wms-admin"));
        when(keycloak.effectiveRealmRoles("kc-3")).thenReturn(List.of("user"));
        when(auraDocs.fetch()).thenReturn(new AuraDocsDirectory(
                List.of(adUser("alice", " alice@acme.COM ", true, List.of("G001", "G-OFF", "G404"), List.of("7", "3"),
                                List.of("ROLE_ADMIN", "ROLE_DESIGNER", "ROLE_UNMAPPED")),
                        adUser("sa", "sa@acme.com", true, List.of("G001"), List.of(), List.of("ROLE_ADMIN"))),
                List.of(new AuraDocsDirectory.Group("G001", "Finance", true),
                        new AuraDocsDirectory.Group("G-OFF", "Retired", false)),
                List.of(new AuraDocsDirectory.Department("3", "Finance"), new AuraDocsDirectory.Department("7", "Audit"))));

        DirectorySnapshot snapshot = source.load();

        assertThat(snapshot.users()).extracting(KcUser::id).containsExactly("kc-1", "kc-2", "kc-3");
        assertThat(snapshot.groups()).containsExactly(
                new KcGroup("adg-G001", "Finance", "/Finance", 0),
                new KcGroup("add-3", "Finance", "/dept/Finance", 0),
                new KcGroup("add-7", "Audit", "/dept/Audit", 0));
        assertThat(snapshot.memberIdsByGroupId()).containsExactly(
                Map.entry("adg-G001", List.of("kc-1")),
                Map.entry("add-3", List.of("kc-1")),
                Map.entry("add-7", List.of("kc-1")));
        KcUser alice = snapshot.users().getFirst();
        assertThat(alice.attribute("department")).isEqualTo("Audit");
        assertThat(alice.attribute("position")).isEqualTo("Clerk");
        assertThat(snapshot.users().get(1).attribute("department")).isEqualTo("Kept");
        assertThat(snapshot.rolesByUserId().get("kc-1"))
                .containsExactly("user", "wms-admin", "process-designer", "document-admin");
        assertThat(snapshot.rolesByUserId().get("kc-2")).containsExactly("user", "wms-admin");
        assertThat(snapshot.rolesByUserId().get("kc-3")).containsExactly("user");
    }

    @Test
    void inactiveAuraDocsUsersGetNothingFromAuraDocs() {
        when(keycloak.listUsers()).thenReturn(List.of(
                new KcUser("kc-1", "alice", "alice@acme.com", null, null, true, Map.of("department", List.of("Old")))));
        when(keycloak.effectiveRealmRoles("kc-1")).thenReturn(List.of("user"));
        when(auraDocs.fetch()).thenReturn(new AuraDocsDirectory(
                List.of(adUser("alice", "alice@acme.com", false, List.of("G1"), List.of("3"), List.of("ROLE_ADMIN"))),
                List.of(new AuraDocsDirectory.Group("G1", "Finance", true)),
                List.of(new AuraDocsDirectory.Department("3", "Finance"))));

        DirectorySnapshot snapshot = source.load();

        assertThat(snapshot.rolesByUserId().get("kc-1")).containsExactly("user");
        assertThat(snapshot.memberIdsByGroupId().values()).allSatisfy(ids -> assertThat(ids).isEmpty());
        assertThat(snapshot.users().getFirst().attribute("department")).isEqualTo("Old");
    }

    @Test
    void aMatchedUserWithoutADepartmentLosesTheKeycloakOne() {
        when(keycloak.listUsers()).thenReturn(List.of(
                new KcUser("kc-1", "alice", "alice@acme.com", null, null, true, Map.of("department", List.of("Old")))));
        when(keycloak.effectiveRealmRoles("kc-1")).thenReturn(List.of());
        when(auraDocs.fetch()).thenReturn(new AuraDocsDirectory(
                List.of(adUser("alice", "alice@acme.com", true, null, null, null)), null, null));

        DirectorySnapshot snapshot = source.load();

        assertThat(snapshot.users().getFirst().attribute("department")).isNull();
        assertThat(snapshot.groups()).isEmpty();
    }

    @Test
    void longIdsAreHashedStablyToFitTheColumn() {
        String longId = "G".repeat(60);
        String id = AuraDocsDirectorySource.groupId("adg-", longId);

        assertThat(id).hasSize(36).startsWith("adg-").isEqualTo(AuraDocsDirectorySource.groupId("adg-", longId));
        assertThat(AuraDocsDirectorySource.groupId("adg-", "H".repeat(60))).isNotEqualTo(id);
        assertThat(AuraDocsDirectorySource.groupId("add-", "x".repeat(32))).isEqualTo("add-" + "x".repeat(32));
        assertThat(AuraDocsDirectorySource.groupId("add-", "x".repeat(33))).hasSize(36).isNotEqualTo("add-" + "x".repeat(32));
    }
}
