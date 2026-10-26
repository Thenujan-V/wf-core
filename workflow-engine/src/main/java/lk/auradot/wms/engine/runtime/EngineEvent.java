package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.definition.BreachAction;

import java.time.Instant;
import java.util.List;

/** What happened during a command; Phase 2B turns these into history rows and outbox events. */
public sealed interface EngineEvent {

    record InstanceStarted(String initiatorId) implements EngineEvent { }
    record NodeEntered(String nodeId, String tokenId) implements EngineEvent { }
    record GatewayRouted(String nodeId, String edgeId) implements EngineEvent { }
    record TaskCreated(String taskId, String nodeId, String assigneeId, List<String> candidates, Instant dueAt) implements EngineEvent { }
    record TaskUnassignable(String taskId, String nodeId, String reason) implements EngineEvent { }
    record TaskClaimed(String taskId, String userId) implements EngineEvent { }
    record TaskUnclaimed(String taskId, String userId) implements EngineEvent { }
    record TaskDelegated(String taskId, String fromUserId, String toUserId) implements EngineEvent { }
    record TaskReassigned(String taskId, String fromUserId, String toUserId, String actorId) implements EngineEvent { }
    record TaskCompleted(String taskId, String nodeId, String userId, String outcome) implements EngineEvent { }
    record TaskCancelled(String taskId, String reason) implements EngineEvent { }
    record NodeDecided(String nodeId, String outcome) implements EngineEvent { }
    record TaskReminderDue(String taskId, String assigneeId) implements EngineEvent { }
    record TaskOverdue(String taskId, String assigneeId, BreachAction action) implements EngineEvent { }
    record TimerFired(String nodeId, String tokenId) implements EngineEvent { }
    record ActionExecuted(String nodeId, ActionType type) implements EngineEvent { }
    record ActionFailed(String nodeId, ActionType type, String error, Instant retryAt) implements EngineEvent { }
    record JobDead(String jobId, String error) implements EngineEvent { }
    record JobRetried(String jobId, String actorId) implements EngineEvent { }
    record InstanceCompleted(String endStatus) implements EngineEvent { }
    record InstanceCancelled(String actorId) implements EngineEvent { }
    record InstanceSuspended(String actorId) implements EngineEvent { }
    record InstanceResumed(String actorId) implements EngineEvent { }
}
