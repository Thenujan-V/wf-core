package lk.auradot.wms.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@WorkflowIntegrationTest
class SchemaMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsWorkflowTables() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name""")
                .query(String.class).list();

        assertThat(tables).containsExactly("execution_token", "fork_scope", "history_event", "job", "outbox",
                "process_definition", "process_draft", "process_instance", "task", "task_candidate");
    }

    @Test
    void whatTheMonitorAndTheDashboardLookRequestsUpByIsIndexed() {
        var indexes = jdbc.sql("""
                        SELECT DISTINCT index_name FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND table_name = 'process_instance'""")
                .query(String.class).list();

        assertThat(indexes).contains("ix_instance_business_key", "ix_instance_process_started", "ix_instance_status_started",
                        "ix_instance_started_process", "ix_instance_ended_cover")
                .doesNotContain("ix_instance_started", "ix_instance_ended"); // replaced by the two wider ones
    }
}
