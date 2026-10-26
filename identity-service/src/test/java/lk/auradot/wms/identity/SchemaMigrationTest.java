package lk.auradot.wms.identity;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@IdentityIntegrationTest
class SchemaMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsIdentityTables() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name""")
                .query(String.class).list();

        assertThat(tables).containsExactly("app_group", "app_user", "sync_lease", "user_group", "user_role");
    }

    @Test
    void seedsFullSyncLease() {
        assertThat(jdbc.sql("SELECT COUNT(*) FROM sync_lease WHERE name = 'identity-full-sync'")
                .query(Integer.class).single()).isEqualTo(1);
    }
}
