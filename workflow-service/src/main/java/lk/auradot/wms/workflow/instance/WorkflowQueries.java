package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.engine.definition.UserTaskNode;
import lk.auradot.wms.engine.runtime.JobStatus;
import lk.auradot.wms.workflow.Json;
import lk.auradot.wms.workflow.Times;
import lk.auradot.wms.workflow.definition.CompiledProcesses;
import lk.auradot.wms.workflow.forms.FormRef;
import lk.auradot.wms.workflow.forms.NodeForms;
import lk.auradot.wms.workflow.job.JobView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read models for the inbox, "my requests", instance detail, history and the admin job monitor. */
@Service
public class WorkflowQueries {

    private static final String TASKS = """
            SELECT t.id, t.instance_id, t.node_id, t.name, t.status, t.assignee_id, t.unassigned_reason,
                   t.created_at, t.due_at, i.process_key, i.business_key, i.initiator_id, i.definition_id
            FROM task t JOIN process_instance i ON i.id = t.instance_id""";
    private static final String INSTANCES = """
            SELECT id, process_key, process_version, business_key, status, end_status, initiator_id, started_at, ended_at
            FROM process_instance""";

    private final JdbcClient jdbc;
    private final Json json;
    private final CompiledProcesses processes;
    private final NodeForms nodeForms;

    public WorkflowQueries(JdbcClient jdbc, Json json, CompiledProcesses processes, NodeForms nodeForms) {
        this.nodeForms = nodeForms;
        this.jdbc = jdbc;
        this.json = json;
        this.processes = processes;
    }

    public List<TaskView> myTasks(String userId) {
        return tasks(TASKS + " WHERE t.status IN ('OPEN', 'CLAIMED') AND t.assignee_id = :user AND i.status = 'ACTIVE'"
                + " ORDER BY t.created_at, t.id", Map.of("user", userId));
    }

    public List<TaskView> claimableTasks(List<String> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        return tasks(TASKS + " " + """
                 WHERE t.status = 'OPEN' AND t.assignee_id IS NULL AND i.status = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM task_candidate c WHERE c.task_id = t.id AND c.candidate IN (:candidates))
                 ORDER BY t.created_at, t.id""", Map.of("candidates", candidates));
    }

    public List<TaskView> allOpenTasks() {
        return tasks(TASKS + " WHERE t.status IN ('OPEN', 'CLAIMED') ORDER BY t.created_at, t.id", Map.of());
    }

    public Optional<InstanceSummary> summary(String instanceId) {
        return jdbc.sql(INSTANCES + " WHERE id = :id").param("id", instanceId).query(this::mapSummary).optional();
    }

    public Optional<InstanceSummary> summaryByBusinessKey(String businessKey) {
        return jdbc.sql(INSTANCES + " WHERE business_key = :key").param("key", businessKey)
                .query(this::mapSummary).optional();
    }

    public List<InstanceSummary> startedBy(String userId) {
        return jdbc.sql(INSTANCES + " WHERE initiator_id = :user ORDER BY started_at DESC, id LIMIT 200")
                .param("user", userId).query(this::mapSummary).list();
    }

    public Optional<InstanceDetail> detail(String instanceId) {
        return summary(instanceId).map(summary -> new InstanceDetail(summary,
                json.readMap(jdbc.sql("SELECT variables_json FROM process_instance WHERE id = :id")
                        .param("id", instanceId).query(String.class).single()),
                tasks(TASKS + " WHERE t.instance_id = :id ORDER BY t.created_at, t.id", Map.of("id", instanceId))));
    }

    public List<HistoryEntry> history(String instanceId) {
        return jdbc.sql("SELECT event_type, actor_id, data_json, occurred_at FROM history_event WHERE instance_id = :id ORDER BY id")
                .param("id", instanceId)
                .query((rs, n) -> new HistoryEntry(rs.getString(1), rs.getString(2), json.readMap(rs.getString(3)),
                        Times.fromDb(rs.getObject(4, LocalDateTime.class))))
                .list();
    }

    /** The request's end and its completed tasks (name, who, decision, when), oldest first. */
    public Optional<InstanceOutcome> outcome(String instanceId) {
        return summary(instanceId).map(s -> {
            String processName = jdbc.sql("""
                            SELECT d.name FROM process_instance i JOIN process_definition d ON d.id = i.definition_id
                            WHERE i.id = :id""")
                    .param("id", instanceId).query(String.class).single();
            List<InstanceOutcome.Step> steps = jdbc.sql("""
                            SELECT name, node_id, completed_by, outcome, completed_at FROM task
                            WHERE instance_id = :id AND completed_at IS NOT NULL AND completed_by IS NOT NULL
                            ORDER BY completed_at, id""")
                    .param("id", instanceId)
                    .query((rs, n) -> new InstanceOutcome.Step(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            Times.fromDb(rs.getObject(5, LocalDateTime.class))))
                    .list();
            return new InstanceOutcome(s.id(), s.processKey(), processName, s.processVersion(), s.businessKey(), s.status(),
                    s.endStatus(), s.initiatorId(), s.startedAt(), s.endedAt(), steps);
        });
    }

    /** The initiator, anyone who holds or held one of its tasks, or anyone in a pool one of its tasks was offered to. */
    public boolean canView(String instanceId, String userId, List<String> candidates) {
        List<String> pools = candidates.isEmpty() ? List.of("-") : candidates;
        return jdbc.sql("""
                        SELECT COUNT(*) FROM process_instance i WHERE i.id = :id AND (
                            i.initiator_id = :user
                            OR EXISTS (SELECT 1 FROM task t WHERE t.instance_id = i.id
                                       AND (t.assignee_id = :user OR t.completed_by = :user))
                            OR EXISTS (SELECT 1 FROM task t JOIN task_candidate c ON c.task_id = t.id
                                       WHERE t.instance_id = i.id AND c.candidate IN (:pools)))""")
                .param("id", instanceId).param("user", userId).param("pools", pools)
                .query(Integer.class).single() > 0;
    }

    public String definitionId(String instanceId) {
        return jdbc.sql("SELECT definition_id FROM process_instance WHERE id = :id").param("id", instanceId)
                .query(String.class).single();
    }

    /** The steps (node ids) a user takes or took part in: as assignee, completer, or member of an offered pool. */
    public Set<String> involvedNodes(String instanceId, String userId, List<String> candidates) {
        List<String> pools = candidates.isEmpty() ? List.of("-") : candidates;
        return new HashSet<>(jdbc.sql("""
                        SELECT DISTINCT t.node_id FROM task t
                        WHERE t.instance_id = :id AND (t.assignee_id = :user OR t.completed_by = :user
                              OR EXISTS (SELECT 1 FROM task_candidate c WHERE c.task_id = t.id AND c.candidate IN (:pools)))""")
                .param("id", instanceId).param("user", userId).param("pools", pools).query(String.class).list());
    }

    /** Spec §6: while a request runs, its initiator and whoever holds one of its open tasks may add documents. */
    public boolean canAttach(String instanceId, String userId) {
        return jdbc.sql("""
                        SELECT COUNT(*) FROM process_instance i WHERE i.id = :id AND (
                            i.initiator_id = :user
                            OR EXISTS (SELECT 1 FROM task t WHERE t.instance_id = i.id AND t.assignee_id = :user
                                       AND t.status IN ('OPEN', 'CLAIMED')))""")
                .param("id", instanceId).param("user", userId).query(Integer.class).single() > 0;
    }

    public List<JobView> jobs(JobStatus status) {
        return jdbc.sql("""
                        SELECT j.id, j.instance_id, j.type, j.status, j.due_at, j.attempts, j.last_error,
                               i.process_key, i.business_key
                        FROM job j JOIN process_instance i ON i.id = j.instance_id
                        WHERE j.status = :status ORDER BY j.due_at, j.id LIMIT 200""")
                .param("status", status.name())
                .query((rs, n) -> new JobView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Times.fromDb(rs.getObject(5, LocalDateTime.class)), rs.getInt(6), rs.getString(7),
                        rs.getString(8), rs.getString(9)))
                .list();
    }

    private record TaskRow(String id, String instanceId, String nodeId, String name, String status, String assigneeId,
                           String unassignedReason, Instant createdAt, Instant dueAt, String processKey,
                           String businessKey, String initiatorId, String definitionId) {
    }

    private List<TaskView> tasks(String sql, Map<String, ?> params) {
        List<TaskRow> rows = jdbc.sql(sql).params(params)
                .query((rs, n) -> new TaskRow(rs.getString("id"), rs.getString("instance_id"), rs.getString("node_id"),
                        rs.getString("name"), rs.getString("status"), rs.getString("assignee_id"),
                        rs.getString("unassigned_reason"), Times.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                        Times.fromDb(rs.getObject("due_at", LocalDateTime.class)), rs.getString("process_key"),
                        rs.getString("business_key"), rs.getString("initiator_id"), rs.getString("definition_id")))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> candidates = new HashMap<>();
        jdbc.sql("SELECT task_id, candidate FROM task_candidate WHERE task_id IN (:ids) ORDER BY candidate")
                .param("ids", rows.stream().map(TaskRow::id).toList())
                .query(rs -> {
                    candidates.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
                });
        return rows.stream().map(r -> {
            FormRef form = nodeForms.of(r.definitionId()).get(r.nodeId());
            return new TaskView(r.id(), r.instanceId(), r.processKey(), r.businessKey(), r.initiatorId(), r.nodeId(),
                    r.name(), r.status(), r.assigneeId(), candidates.getOrDefault(r.id(), List.of()),
                    outcomes(r.definitionId(), r.nodeId()), r.unassignedReason(), r.createdAt(), r.dueAt(),
                    form == null ? null : form.key(), form == null ? null : form.version());
        }).toList();
    }

    private List<String> outcomes(String definitionId, String nodeId) {
        return processes.get(definitionId).node(nodeId) instanceof UserTaskNode task ? task.outcomes() : List.of();
    }

    private InstanceSummary mapSummary(ResultSet rs, int row) throws SQLException {
        return new InstanceSummary(rs.getString("id"), rs.getString("process_key"), rs.getInt("process_version"),
                rs.getString("business_key"), rs.getString("status"), rs.getString("end_status"),
                rs.getString("initiator_id"), Times.fromDb(rs.getObject("started_at", LocalDateTime.class)),
                Times.fromDb(rs.getObject("ended_at", LocalDateTime.class)));
    }
}
