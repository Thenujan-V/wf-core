package lk.auradot.wms.workflow;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;

/** Requests written straight into the tables, for tests about lists and statistics that need exact dates. */
public final class InstanceFixture {

    private final JdbcClient jdbc;

    public InstanceFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void definition(String processKey, String name) {
        jdbc.sql("""
                        INSERT INTO process_definition (id, process_key, version, name, status, graph_json, published_at, published_by)
                        VALUES (:id, :key, 1, :name, 'PUBLISHED', '{}', :at, 'bob')""")
                .param("id", "def-" + processKey).param("key", processKey).param("name", name)
                .param("at", Times.toDb(Instant.parse("2026-01-01T00:00:00Z"))).update();
    }

    public void instance(String id, String processKey, String businessKey, String status, String endStatus,
                         String initiatorId, String startedAt, String endedAt) {
        jdbc.sql("""
                        INSERT INTO process_instance (id, definition_id, process_key, process_version, business_key, status,
                                                      end_status, variables_json, initiator_id, started_at, ended_at, version)
                        VALUES (:id, :definition, :key, 1, :businessKey, :status, :endStatus, '{}', :initiator, :started, :ended, 0)""")
                .param("id", id).param("definition", "def-" + processKey).param("key", processKey)
                .param("businessKey", businessKey).param("status", status).param("endStatus", endStatus)
                .param("initiator", initiatorId).param("started", Times.toDb(Instant.parse(startedAt)))
                .param("ended", endedAt == null ? null : Times.toDb(Instant.parse(endedAt))).update();
    }

    /** A task of this request went past its deadline. */
    public void breach(String instanceId, String at) {
        jdbc.sql("""
                        INSERT INTO history_event (instance_id, event_type, actor_id, data_json, occurred_at)
                        VALUES (:instance, 'TaskOverdue', NULL, '{}', :at)""")
                .param("instance", instanceId).param("at", Times.toDb(Instant.parse(at))).update();
    }

    public void deadJob(String id, String instanceId, String lastError) {
        jdbc.sql("""
                        INSERT INTO job (id, instance_id, type, token_id, due_at, status, attempts, last_error)
                        VALUES (:id, :instance, 'ASYNC_ACTION', 'token-1', :due, 'DEAD', 5, :error)""")
                .param("id", id).param("instance", instanceId)
                .param("due", Times.toDb(Instant.parse("2026-09-27T12:00:00Z"))).param("error", lastError).update();
    }
}
