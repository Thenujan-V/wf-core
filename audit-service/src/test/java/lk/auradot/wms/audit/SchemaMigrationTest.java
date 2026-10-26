package lk.auradot.wms.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AuditIntegrationTest
class SchemaMigrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired AuditAdminDb admin;

    @BeforeEach
    void reset() {
        admin.clear();
    }

    @Test
    void createsTheAuditTable() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name""")
                .query(String.class).list();
        assertThat(tables).containsExactly("audit_entry");
    }

    /** Spec §8: "Its DB user has INSERT/SELECT only". */
    @Test
    void theServicesOwnDatabaseUserCanAddAndReadEntriesButNeverChangeThem() {
        jdbc.sql("""
                        INSERT INTO audit_entry (message_id, action, entity_type, data_json, occurred_at, recorded_at)
                        VALUES ('m-1', 'workflow.instance.started', 'INSTANCE', '{}', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))""")
                .update();

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit_entry SET actor_id = 'mallory'").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit_entry").update())
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM audit_entry WHERE actor_id IS NULL").query(Long.class).single()).isEqualTo(1);
    }
}
