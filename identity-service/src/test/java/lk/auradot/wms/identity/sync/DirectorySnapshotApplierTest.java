package lk.auradot.wms.identity.sync;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import lk.auradot.wms.identity.org.OrgSource;
import lk.auradot.wms.identity.org.OrgSourceProperties;
import lk.auradot.wms.identity.user.UserWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IdentityIntegrationTest
class DirectorySnapshotApplierTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    UserWriter writer;
    @Autowired
    DirectorySnapshotApplier applier;
    TestData data;

    @BeforeEach
    void reset() {
        data = new TestData(jdbc);
        data.clear();
    }

    private static KcUser kc(String id, String username, Map<String, List<String>> attributes) {
        return new KcUser(id, username, username + "@example.com", username, "Test", true, attributes);
    }

    private String column(String column, String userId) {
        // list() keeps SQL NULLs; single() would throw on them.
        List<String> values = jdbc.sql("SELECT " + column + " FROM app_user WHERE id = :id")
                .param("id", userId).query(String.class).list();
        assertThat(values).hasSize(1);
        return values.get(0);
    }

    private DirectorySnapshot orgSnapshot() {
        return new DirectorySnapshot(
                List.of(kc("a", "alice", Map.of("department", List.of("Management"))),
                        kc("b", "bob", Map.of("department", List.of("Finance"), "manager", List.of("Alice"))),
                        kc("c", "carol", Map.of("department", List.of("Finance"), "position", List.of("Accountant"),
                                "manager", List.of("bob"))),
                        kc("x", "xavier", Map.of("manager", List.of("nobody"))),
                        kc("s", "selfie", Map.of("manager", List.of("selfie")))),
                Map.of("a", List.of("wms-admin", "user"), "b", List.of("user"), "c", List.of("user")),
                List.of(new KcGroup("g-fin", "Finance", "/Finance", 0), new KcGroup("g-sup", "Support", "/IT/Support", 0)),
                Map.of("g-fin", List.of("b", "c", "not-a-user"), "g-sup", List.of()));
    }

    @Test
    void appliesUsersRolesGroupsMembershipsAndManagers() {
        SyncResult result = applier.apply(orgSnapshot(), Instant.now());

        assertThat(result.users()).isEqualTo(5);
        assertThat(result.groups()).isEqualTo(2);
        assertThat(column("manager_id", "c")).isEqualTo("b");
        assertThat(column("manager_id", "b")).as("manager usernames match case-insensitively").isEqualTo("a");
        assertThat(column("manager_id", "x")).as("unknown manager").isNull();
        assertThat(column("manager_id", "s")).as("self-reference").isNull();
        assertThat(column("position_title", "c")).isEqualTo("Accountant");
        assertThat(jdbc.sql("SELECT role_name FROM user_role WHERE user_id = 'a' ORDER BY role_name")
                .query(String.class).list()).containsExactly("user", "wms-admin");
        assertThat(jdbc.sql("SELECT user_id FROM user_group WHERE group_id = 'g-fin' ORDER BY user_id")
                .query(String.class).list()).containsExactly("b", "c");
    }

    @Test
    void disablesUsersMissingFromDirectoryWithoutDeletingThem() {
        data.user("gone", "gone", "Gone", "User");

        SyncResult result = applier.apply(orgSnapshot(), Instant.now());

        assertThat(result.disabledUsers()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT enabled FROM app_user WHERE id = 'gone'").query(Boolean.class).single()).isFalse();
    }

    @Test
    void removesGroupsDeletedInKeycloak() {
        data.group("old", "Old");

        applier.apply(orgSnapshot(), Instant.now());

        assertThat(jdbc.sql("SELECT id FROM app_group ORDER BY id").query(String.class).list())
                .containsExactly("g-fin", "g-sup");
    }

    @Test
    void refusesEmptyUserSnapshotAndChangesNothing() {
        data.user("keep", "keep", "Keep", "Me");

        assertThatThrownBy(() -> applier.apply(new DirectorySnapshot(List.of(), Map.of(), List.of(), Map.of()), Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT enabled FROM app_user WHERE id = 'keep'").query(Boolean.class).single()).isTrue();
    }

    @Test
    void leavesLocalOrgFieldsUntouched() {
        data.user("c", "carol", "Carol", "Clerk", null, "Admin-set department", true);
        var localDepartment = new DirectorySnapshotApplier(jdbc, writer,
                new OrgSourceProperties(OrgSource.KEYCLOAK, OrgSource.LOCAL, OrgSource.KEYCLOAK));

        localDepartment.apply(orgSnapshot(), Instant.now());

        assertThat(column("department", "c")).isEqualTo("Admin-set department");
        assertThat(column("manager_id", "c")).isEqualTo("b");
    }
}
