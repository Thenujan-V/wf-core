package lk.auradot.wms.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@NotificationIntegrationTest
class SchemaMigrationTest {

    @Autowired JdbcClient jdbc;

    @Test
    void createsTheNotificationTables() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name""")
                .query(String.class).list();
        assertThat(tables).containsExactly("email_message", "notification", "notification_preference", "processed_message");
    }
}
