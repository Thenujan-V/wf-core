package lk.auradot.wms.form;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@FormIntegrationTest
class SchemaMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsFormTables() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name""")
                .query(String.class).list();

        assertThat(tables).containsExactly("form_definition", "form_draft", "form_submission");
    }
}
