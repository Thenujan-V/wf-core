package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.engine.runtime.ForkScope;
import lk.auradot.wms.engine.runtime.InstanceState;
import lk.auradot.wms.engine.runtime.InstanceStatus;
import lk.auradot.wms.engine.runtime.Job;
import lk.auradot.wms.engine.runtime.JobStatus;
import lk.auradot.wms.engine.runtime.JobType;
import lk.auradot.wms.engine.runtime.Task;
import lk.auradot.wms.engine.runtime.TaskStatus;
import lk.auradot.wms.engine.runtime.Token;
import lk.auradot.wms.engine.runtime.TokenStatus;
import lk.auradot.wms.workflow.Json;
import lk.auradot.wms.workflow.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads and saves whole instances. The instance row carries the optimistic version; child rows are upserted
 * on every save (instances are small). Job lease columns are never touched here.
 */
@Repository
public class InstanceStore {

    private final JdbcClient jdbc;
    private final Json json;

    public InstanceStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Who started the request a business key names; the key is unique (V4). */
    public record KeyedInstance(String id, String processKey, String initiatorId) {
    }

    public Optional<KeyedInstance> findByBusinessKey(String businessKey) {
        return jdbc.sql("SELECT id, process_key, initiator_id FROM process_instance WHERE business_key = :key")
                .param("key", businessKey)
                .query((rs, n) -> new KeyedInstance(rs.getString("id"), rs.getString("process_key"),
                        rs.getString("initiator_id")))
                .optional();
    }

    public void insert(String definitionId, InstanceState s) {
        jdbc.sql("""
                        INSERT INTO process_instance (id, definition_id, process_key, process_version, business_key,
                            status, end_status, variables_json, initiator_id, started_at, ended_at, version)
                        VALUES (:id, :definitionId, :key, :processVersion, :businessKey, :status, :endStatus,
                            :variables, :initiator, :startedAt, :endedAt, 0)""")
                .param("id", s.id()).param("definitionId", definitionId).param("key", s.processKey())
                .param("processVersion", s.processVersion()).param("businessKey", s.businessKey())
                .param("status", s.status().name()).param("endStatus", s.endStatus())
                .param("variables", json.write(s.variables())).param("initiator", s.initiatorId())
                .param("startedAt", Times.toDb(s.startedAt())).param("endedAt", Times.toDb(s.endedAt()))
                .update();
        saveChildren(s);
    }

    public void update(LoadedInstance loaded) {
        InstanceState s = loaded.state();
        int changed = jdbc.sql("""
                        UPDATE process_instance SET status = :status, end_status = :endStatus,
                            variables_json = :variables, ended_at = :endedAt, version = version + 1
                        WHERE id = :id AND version = :version""")
                .param("status", s.status().name()).param("endStatus", s.endStatus())
                .param("variables", json.write(s.variables())).param("endedAt", Times.toDb(s.endedAt()))
                .param("id", s.id()).param("version", loaded.version())
                .update();
        if (changed == 0) {
            throw new OptimisticConflictException(s.id());
        }
        saveChildren(s);
    }

    private record InstanceRow(String id, String definitionId, String processKey, int processVersion,
                               String businessKey, String status, String endStatus, String variablesJson,
                               String initiatorId, Instant startedAt, Instant endedAt, int version) {
    }

    public Optional<LoadedInstance> load(String id) {
        return jdbc.sql("SELECT * FROM process_instance WHERE id = :id").param("id", id)
                .query((rs, n) -> new InstanceRow(rs.getString("id"), rs.getString("definition_id"),
                        rs.getString("process_key"), rs.getInt("process_version"), rs.getString("business_key"),
                        rs.getString("status"), rs.getString("end_status"), rs.getString("variables_json"),
                        rs.getString("initiator_id"), Times.fromDb(rs.getObject("started_at", LocalDateTime.class)),
                        Times.fromDb(rs.getObject("ended_at", LocalDateTime.class)), rs.getInt("version")))
                .optional()
                .map(row -> new LoadedInstance(row.definitionId(), row.version(), new InstanceState(row.id(),
                        row.processKey(), row.processVersion(), row.initiatorId(), row.businessKey(), row.startedAt(),
                        InstanceStatus.valueOf(row.status()), row.endStatus(), row.endedAt(),
                        json.readMap(row.variablesJson()), tokens(id), scopes(id), tasks(id), jobs(id))));
    }

    public Optional<String> instanceOfTask(String taskId) {
        return jdbc.sql("SELECT instance_id FROM task WHERE id = :id").param("id", taskId).query(String.class).optional();
    }

    public Optional<String> instanceOfJob(String jobId) {
        return jdbc.sql("SELECT instance_id FROM job WHERE id = :id").param("id", jobId).query(String.class).optional();
    }

    private List<Token> tokens(String instanceId) {
        return jdbc.sql("SELECT id, node_id, status, scope_id FROM execution_token WHERE instance_id = :id")
                .param("id", instanceId)
                .query((rs, n) -> new Token(rs.getString("id"), rs.getString("node_id"),
                        TokenStatus.valueOf(rs.getString("status")), rs.getString("scope_id")))
                .list();
    }

    private List<ForkScope> scopes(String instanceId) {
        return jdbc.sql("""
                        SELECT id, parent_scope_id, split_node_id, join_node_id, expected, arrived
                        FROM fork_scope WHERE instance_id = :id""")
                .param("id", instanceId)
                .query((rs, n) -> new ForkScope(rs.getString("id"), rs.getString("parent_scope_id"),
                        rs.getString("split_node_id"), rs.getString("join_node_id"), rs.getInt("expected"),
                        rs.getInt("arrived")))
                .list();
    }

    private List<Task> tasks(String instanceId) {
        Map<String, List<String>> candidates = new HashMap<>();
        jdbc.sql("""
                        SELECT c.task_id, c.candidate FROM task_candidate c JOIN task t ON t.id = c.task_id
                        WHERE t.instance_id = :id ORDER BY c.candidate""")
                .param("id", instanceId)
                .query(rs -> {
                    candidates.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return jdbc.sql("SELECT * FROM task WHERE instance_id = :id ORDER BY created_at, id")
                .param("id", instanceId)
                .query((rs, n) -> new Task(rs.getString("id"), rs.getString("token_id"), rs.getString("activation_id"),
                        rs.getString("node_id"), rs.getString("name"), TaskStatus.valueOf(rs.getString("status")),
                        rs.getString("assignee_id"), candidates.getOrDefault(rs.getString("id"), List.of()),
                        rs.getString("outcome"), rs.getString("completed_by"), rs.getString("unassigned_reason"),
                        Times.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                        Times.fromDb(rs.getObject("due_at", LocalDateTime.class)),
                        Times.fromDb(rs.getObject("completed_at", LocalDateTime.class))))
                .list();
    }

    private List<Job> jobs(String instanceId) {
        return jdbc.sql("SELECT * FROM job WHERE instance_id = :id")
                .param("id", instanceId)
                .query((rs, n) -> new Job(rs.getString("id"), JobType.valueOf(rs.getString("type")),
                        rs.getString("token_id"), rs.getString("task_id"),
                        Times.fromDb(rs.getObject("due_at", LocalDateTime.class)),
                        JobStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getString("last_error")))
                .list();
    }

    private void saveChildren(InstanceState s) {
        for (Token token : s.tokens()) {
            jdbc.sql("""
                            INSERT INTO execution_token (id, instance_id, node_id, status, scope_id)
                            VALUES (:id, :instance, :node, :status, :scope) AS new
                            ON DUPLICATE KEY UPDATE node_id = new.node_id, status = new.status""")
                    .param("id", token.id()).param("instance", s.id()).param("node", token.nodeId())
                    .param("status", token.status().name()).param("scope", token.scopeId())
                    .update();
        }
        for (ForkScope scope : s.scopes()) {
            jdbc.sql("""
                            INSERT INTO fork_scope (id, instance_id, parent_scope_id, split_node_id, join_node_id, expected, arrived)
                            VALUES (:id, :instance, :parent, :split, :join, :expected, :arrived) AS new
                            ON DUPLICATE KEY UPDATE arrived = new.arrived""")
                    .param("id", scope.id()).param("instance", s.id()).param("parent", scope.parentScopeId())
                    .param("split", scope.splitNodeId()).param("join", scope.joinNodeId())
                    .param("expected", scope.expected()).param("arrived", scope.arrived())
                    .update();
        }
        for (Task task : s.tasks()) {
            jdbc.sql("""
                            INSERT INTO task (id, instance_id, token_id, activation_id, node_id, name, status, assignee_id,
                                outcome, completed_by, unassigned_reason, created_at, due_at, completed_at)
                            VALUES (:id, :instance, :token, :activation, :node, :name, :status, :assignee, :outcome,
                                :completedBy, :reason, :createdAt, :dueAt, :completedAt) AS new
                            ON DUPLICATE KEY UPDATE status = new.status, assignee_id = new.assignee_id,
                                outcome = new.outcome, completed_by = new.completed_by,
                                unassigned_reason = new.unassigned_reason, completed_at = new.completed_at""")
                    .param("id", task.id()).param("instance", s.id()).param("token", task.tokenId())
                    .param("activation", task.activationId()).param("node", task.nodeId()).param("name", task.name())
                    .param("status", task.status().name()).param("assignee", task.assigneeId())
                    .param("outcome", task.outcome()).param("completedBy", task.completedBy())
                    .param("reason", task.unassignedReason()).param("createdAt", Times.toDb(task.createdAt()))
                    .param("dueAt", Times.toDb(task.dueAt())).param("completedAt", Times.toDb(task.completedAt()))
                    .update();
            for (String candidate : task.candidates()) {
                jdbc.sql("INSERT IGNORE INTO task_candidate (task_id, candidate) VALUES (:task, :candidate)")
                        .param("task", task.id()).param("candidate", candidate).update();
            }
        }
        for (Job job : s.jobs()) {
            jdbc.sql("""
                            INSERT INTO job (id, instance_id, type, token_id, task_id, due_at, status, attempts, last_error)
                            VALUES (:id, :instance, :type, :token, :task, :dueAt, :status, :attempts, :error) AS new
                            ON DUPLICATE KEY UPDATE due_at = new.due_at, status = new.status,
                                attempts = new.attempts, last_error = new.last_error""")
                    .param("id", job.id()).param("instance", s.id()).param("type", job.type().name())
                    .param("token", job.tokenId()).param("task", job.taskId()).param("dueAt", Times.toDb(job.dueAt()))
                    .param("status", job.status().name()).param("attempts", job.attempts())
                    .param("error", truncate(job.lastError()))
                    .update();
        }
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 2000 ? text : text.substring(0, 2000);
    }
}
