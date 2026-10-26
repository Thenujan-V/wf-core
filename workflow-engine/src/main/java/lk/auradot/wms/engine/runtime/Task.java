package lk.auradot.wms.engine.runtime;

import java.time.Instant;
import java.util.List;

/**
 * A work item. Tasks created by one arrival at a user-task node share an {@code activationId}; multi-approver
 * rules look at that group. {@code candidates} ("group:x" / "role:y") are set for claimable tasks.
 */
public final class Task {

    private final String id;
    private final String tokenId;
    private final String activationId;
    private final String nodeId;
    private final String name;
    private TaskStatus status;
    private String assigneeId;
    private final List<String> candidates;
    private String outcome;
    private String completedBy;
    private String unassignedReason;
    private final Instant createdAt;
    private final Instant dueAt;
    private Instant completedAt;

    public Task(String id, String tokenId, String activationId, String nodeId, String name, TaskStatus status,
                String assigneeId, List<String> candidates, String outcome, String completedBy,
                String unassignedReason, Instant createdAt, Instant dueAt, Instant completedAt) {
        this.id = id;
        this.tokenId = tokenId;
        this.activationId = activationId;
        this.nodeId = nodeId;
        this.name = name;
        this.status = status;
        this.assigneeId = assigneeId;
        this.candidates = List.copyOf(candidates);
        this.outcome = outcome;
        this.completedBy = completedBy;
        this.unassignedReason = unassignedReason;
        this.createdAt = createdAt;
        this.dueAt = dueAt;
        this.completedAt = completedAt;
    }

    public String id() { return id; }
    public String tokenId() { return tokenId; }
    public String activationId() { return activationId; }
    public String nodeId() { return nodeId; }
    public String name() { return name; }
    public TaskStatus status() { return status; }
    public String assigneeId() { return assigneeId; }
    public List<String> candidates() { return candidates; }
    public String outcome() { return outcome; }
    public String completedBy() { return completedBy; }
    public String unassignedReason() { return unassignedReason; }
    public Instant createdAt() { return createdAt; }
    public Instant dueAt() { return dueAt; }
    public Instant completedAt() { return completedAt; }

    public boolean isOpen() {
        return status == TaskStatus.OPEN || status == TaskStatus.CLAIMED;
    }

    void claim(String userId) {
        assigneeId = userId;
        status = TaskStatus.CLAIMED;
    }

    void unclaim() {
        assigneeId = null;
        status = TaskStatus.OPEN;
    }

    void assign(String userId) {
        assigneeId = userId;
        unassignedReason = null;
        status = TaskStatus.OPEN;
    }

    void complete(String userId, String outcome, Instant at) {
        this.status = TaskStatus.COMPLETED;
        this.completedBy = userId;
        this.outcome = outcome;
        this.completedAt = at;
    }

    void cancel(Instant at) {
        status = TaskStatus.CANCELLED;
        completedAt = at;
    }
}
