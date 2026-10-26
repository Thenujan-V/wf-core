package lk.auradot.wms.identity.user;

import lk.auradot.wms.identity.org.OrgField;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.TreeSet;

@Repository
public class UserWriter {

    private final JdbcClient jdbc;

    public UserWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts or updates the Keycloak-owned profile fields; never touches org fields. */
    public void upsertCore(UserRecord user, Instant syncedAt) {
        jdbc.sql("""
                        INSERT INTO app_user (id, username, email, first_name, last_name, enabled, synced_at)
                        VALUES (:id, :username, :email, :firstName, :lastName, :enabled, :syncedAt) AS new
                        ON DUPLICATE KEY UPDATE username = new.username, email = new.email,
                            first_name = new.first_name, last_name = new.last_name,
                            enabled = new.enabled, synced_at = new.synced_at""")
                .param("id", user.id())
                .param("username", user.username())
                .param("email", user.email())
                .param("firstName", user.firstName())
                .param("lastName", user.lastName())
                .param("enabled", user.enabled())
                .param("syncedAt", Times.toDb(syncedAt))
                .update();
    }

    public void updateOrg(String userId, OrgField field, String value) {
        // The column name comes from the enum constant, never from input.
        jdbc.sql("UPDATE app_user SET " + field.column() + " = :value WHERE id = :id")
                .param("value", value)
                .param("id", userId)
                .update();
    }

    public void replaceRoles(String userId, Collection<String> roles) {
        TreeSet<String> wanted = new TreeSet<>(roles);
        if (wanted.isEmpty()) {
            jdbc.sql("DELETE FROM user_role WHERE user_id = :userId").param("userId", userId).update();
            return;
        }
        jdbc.sql("DELETE FROM user_role WHERE user_id = :userId AND role_name NOT IN (:roles)")
                .param("userId", userId).param("roles", wanted).update();
        for (String role : wanted) {
            jdbc.sql("INSERT IGNORE INTO user_role (user_id, role_name) VALUES (:userId, :role)")
                    .param("userId", userId).param("role", role).update();
        }
    }
}
