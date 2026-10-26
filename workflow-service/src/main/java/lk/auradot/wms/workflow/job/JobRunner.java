package lk.auradot.wms.workflow.job;

import lk.auradot.wms.engine.runtime.ActionRequest;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.workflow.instance.WorkflowCommands;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Runs one claimed job. External actions happen between two short transactions, never inside one. */
@Component
public class JobRunner {

    private final WorkflowCommands commands;
    private final ProcessEngine engine;
    private final ActionRunner actions;

    public JobRunner(WorkflowCommands commands, ProcessEngine engine, ActionRunner actions) {
        this.commands = commands;
        this.engine = engine;
        this.actions = actions;
    }

    public void run(ClaimedJob job) {
        Optional<ActionRequest> request = commands.read(job.instanceId(), (p, s) -> engine.actionRequest(p, s, job.jobId()));
        if (request.isEmpty()) {
            commands.onInstance(job.instanceId(), WorkflowCommands.SYSTEM, (p, s) -> engine.fireJob(p, s, job.jobId()));
            return;
        }
        ActionResult result = actions.run(request.get());
        commands.onInstance(job.instanceId(), WorkflowCommands.SYSTEM, (p, s) -> {
            if (result.success()) {
                engine.actionSucceeded(p, s, job.jobId());
            } else {
                engine.actionFailed(p, s, job.jobId(), result.error(), result.retryable());
            }
        });
    }
}
