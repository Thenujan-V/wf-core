package lk.auradot.wms.engine.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Everything about one running process instance. The engine mutates it; Phase 2B persists it. */
public final class InstanceState {

    private final String id;
    private final String processKey;
    private final int processVersion;
    private final String initiatorId;
    private final String businessKey;
    private final Instant startedAt;
    private InstanceStatus status;
    private String endStatus;
    private Instant endedAt;
    private final Map<String, Object> variables;
    private final List<Token> tokens;
    private final List<ForkScope> scopes;
    private final List<Task> tasks;
    private final List<Job> jobs;
    private final List<EngineEvent> events = new ArrayList<>();

    public InstanceState(String id, String processKey, int processVersion, String initiatorId, String businessKey,
                         Instant startedAt, InstanceStatus status, String endStatus, Instant endedAt,
                         Map<String, Object> variables, List<Token> tokens, List<ForkScope> scopes,
                         List<Task> tasks, List<Job> jobs) {
        this.id = id;
        this.processKey = processKey;
        this.processVersion = processVersion;
        this.initiatorId = initiatorId;
        this.businessKey = businessKey;
        this.startedAt = startedAt;
        this.status = status;
        this.endStatus = endStatus;
        this.endedAt = endedAt;
        this.variables = new LinkedHashMap<>(variables);
        this.tokens = new ArrayList<>(tokens);
        this.scopes = new ArrayList<>(scopes);
        this.tasks = new ArrayList<>(tasks);
        this.jobs = new ArrayList<>(jobs);
    }

    public String id() { return id; }
    public String processKey() { return processKey; }
    public int processVersion() { return processVersion; }
    public String initiatorId() { return initiatorId; }
    public String businessKey() { return businessKey; }
    public Instant startedAt() { return startedAt; }
    public InstanceStatus status() { return status; }
    public String endStatus() { return endStatus; }
    public Instant endedAt() { return endedAt; }

    /** Live, mutable variable tree (the engine writes into it). */
    public Map<String, Object> variables() { return variables; }

    public List<Token> tokens() { return Collections.unmodifiableList(tokens); }
    public List<ForkScope> scopes() { return Collections.unmodifiableList(scopes); }
    public List<Task> tasks() { return Collections.unmodifiableList(tasks); }
    public List<Job> jobs() { return Collections.unmodifiableList(jobs); }

    public Optional<Task> task(String taskId) {
        return tasks.stream().filter(t -> t.id().equals(taskId)).findFirst();
    }

    public Optional<Job> job(String jobId) {
        return jobs.stream().filter(j -> j.id().equals(jobId)).findFirst();
    }

    public List<Task> openTasks() {
        return tasks.stream().filter(Task::isOpen).toList();
    }

    /** Returns and clears the events recorded since the last call. */
    public List<EngineEvent> drainEvents() {
        List<EngineEvent> drained = List.copyOf(events);
        events.clear();
        return drained;
    }

    Token token(String tokenId) {
        return tokens.stream().filter(t -> t.id().equals(tokenId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown token " + tokenId));
    }

    ForkScope scope(String scopeId) {
        return scopes.stream().filter(s -> s.id().equals(scopeId)).findFirst().orElse(null);
    }

    List<Task> tasksOfActivation(String activationId) {
        return tasks.stream().filter(t -> t.activationId().equals(activationId)).toList();
    }

    List<Job> jobsOfTask(String taskId) {
        return jobs.stream().filter(j -> taskId.equals(j.taskId())).toList();
    }

    Token addToken(Token token) { tokens.add(token); return token; }
    ForkScope addScope(ForkScope scope) { scopes.add(scope); return scope; }
    Task addTask(Task task) { tasks.add(task); return task; }
    Job addJob(Job job) { jobs.add(job); return job; }

    void record(EngineEvent event) { events.add(event); }

    void setStatus(InstanceStatus status) { this.status = status; }

    void finish(InstanceStatus status, Instant at) {
        this.status = status;
        this.endedAt = at;
    }

    void setEndStatus(String endStatus) { this.endStatus = endStatus; }
}
