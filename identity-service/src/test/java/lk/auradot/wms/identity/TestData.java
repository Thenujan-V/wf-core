package lk.auradot.wms.identity;

import lk.auradot.wms.identity.user.Times;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;

/** SQL fixtures for identity-service integration tests. */
public class TestData {

    private final JdbcClient jdbc;

    public TestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.sql("DELETE FROM user_group").update();
        jdbc.sql("DELETE FROM user_role").update();
        jdbc.sql("UPDATE app_user SET manager_id = NULL").update();
        jdbc.sql("DELETE FROM app_user").update();
        jdbc.sql("DELETE FROM app_group").update();
        jdbc.sql("UPDATE sync_lease SET owner = NULL, lease_until = '1970-01-01 00:00:00'").update();
    }

    public void user(String id, String username, String firstName, String lastName) {
        user(id, username, firstName, lastName, null, null, true);
    }

    public void user(String id, String username, String firstName, String lastName,
                     String managerId, String department, boolean enabled) {
        jdbc.sql("""
                        INSERT INTO app_user (id, username, email, first_name, last_name, enabled, department, manager_id, synced_at)
                        VALUES (:id, :username, :email, :firstName, :lastName, :enabled, :department, :managerId, :syncedAt)""")
                .param("id", id).param("username", username).param("email", username + "@example.com")
                .param("firstName", firstName).param("lastName", lastName).param("enabled", enabled)
                .param("department", department).param("managerId", managerId)
                .param("syncedAt", Times.toDb(Instant.now()))
                .update();
    }

    public void role(String userId, String role) {
        jdbc.sql("INSERT INTO user_role (user_id, role_name) VALUES (:u, :r)").param("u", userId).param("r", role).update();
    }

    public void group(String id, String name) {
        jdbc.sql("INSERT INTO app_group (id, name, path) VALUES (:id, :name, :path)")
                .param("id", id).param("name", name).param("path", "/" + name).update();
    }

    public void member(String groupId, String userId) {
        jdbc.sql("INSERT INTO user_group (user_id, group_id) VALUES (:u, :g)").param("u", userId).param("g", groupId).update();
    }

    public void syncedAt(String userId, Instant at) {
        jdbc.sql("UPDATE app_user SET synced_at = :at WHERE id = :id").param("at", Times.toDb(at)).param("id", userId).update();
    }
}
