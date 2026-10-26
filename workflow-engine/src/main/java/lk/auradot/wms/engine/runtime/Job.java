package lk.auradot.wms.engine.runtime;

import java.time.Instant;

/** Something that must happen at {@code dueAt}: a timer, an SLA reminder/breach, or a system action. */
public final class Job {

    private final String id;
    private final JobType type;
    private final String tokenId;
    private final String taskId;
    private Instant dueAt;
    private JobStatus status;
    private int attempts;
    private String lastError;

    public Job(String id, JobType type, String tokenId, String taskId, Instant dueAt, JobStatus status,
               int attempts, String lastError) {
        this.id = id;
        this.type = type;
        this.tokenId = tokenId;
        this.taskId = taskId;
        this.dueAt = dueAt;
        this.status = status;
        this.attempts = attempts;
        this.lastError = lastError;
    }

    static Job pending(String id, JobType type, String tokenId, String taskId, Instant dueAt) {
        return new Job(id, type, tokenId, taskId, dueAt, JobStatus.PENDING, 0, null);
    }

    public String id() { return id; }
    public JobType type() { return type; }
    public String tokenId() { return tokenId; }
    public String taskId() { return taskId; }
    public Instant dueAt() { return dueAt; }
    public JobStatus status() { return status; }
    public int attempts() { return attempts; }
    public String lastError() { return lastError; }

    void done() {
        status = JobStatus.DONE;
    }

    void cancel() {
        status = JobStatus.CANCELLED;
    }

    void suspend() {
        status = JobStatus.SUSPENDED;
    }

    void resume(Instant now) {
        status = JobStatus.PENDING;
        if (dueAt.isBefore(now)) {
            dueAt = now;
        }
    }

    void attempt() {
        attempts++;
    }

    void retryAt(Instant at, String error) {
        status = JobStatus.PENDING;
        dueAt = at;
        lastError = error;
    }

    void dead(String error) {
        status = JobStatus.DEAD;
        lastError = error;
    }

    void reset(Instant now) {
        status = JobStatus.PENDING;
        attempts = 0;
        dueAt = now;
    }
}
