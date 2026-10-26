package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.runtime.EngineEvent;

import java.util.Optional;

/** Which engine events are published, and under which routing key. */
public final class EventMapper {

    private EventMapper() {
    }

    public static String type(EngineEvent event) {
        return event.getClass().getSimpleName();
    }

    public static Optional<String> routingKey(EngineEvent event) {
        return Optional.ofNullable(switch (event) {
            case EngineEvent.InstanceStarted _ -> "workflow.instance.started";
            case EngineEvent.InstanceCompleted _ -> "workflow.instance.completed";
            case EngineEvent.InstanceCancelled _ -> "workflow.instance.cancelled";
            case EngineEvent.InstanceSuspended _ -> "workflow.instance.suspended";
            case EngineEvent.InstanceResumed _ -> "workflow.instance.resumed";
            case EngineEvent.TaskCreated _ -> "workflow.task.created";
            case EngineEvent.TaskUnassignable _ -> "workflow.task.unassignable";
            case EngineEvent.TaskClaimed _ -> "workflow.task.claimed";
            case EngineEvent.TaskUnclaimed _ -> "workflow.task.unclaimed";
            case EngineEvent.TaskDelegated _ -> "workflow.task.delegated";
            case EngineEvent.TaskReassigned _ -> "workflow.task.reassigned";
            case EngineEvent.TaskCompleted _ -> "workflow.task.completed";
            case EngineEvent.TaskCancelled _ -> "workflow.task.cancelled";
            case EngineEvent.TaskReminderDue _ -> "workflow.task.reminder";
            case EngineEvent.TaskOverdue _ -> "workflow.task.overdue";
            case EngineEvent.JobDead _ -> "workflow.job.dead";
            // Spec §4: retrying a dead job is an administrator action, and all of those are audited.
            case EngineEvent.JobRetried _ -> "workflow.job.retried";
            case EngineEvent.ActionExecuted executed -> executed.type() == ActionType.SEND_EMAIL ? "workflow.email.requested" : null;
            case EngineEvent.NodeEntered _, EngineEvent.GatewayRouted _, EngineEvent.NodeDecided _,
                 EngineEvent.TimerFired _, EngineEvent.ActionFailed _ -> null;
        });
    }
}
