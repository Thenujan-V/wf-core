package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.UserTaskNode;
import lk.auradot.wms.engine.runtime.InstanceState;
import lk.auradot.wms.engine.runtime.InstanceStatus;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.engine.runtime.Task;
import lk.auradot.wms.workflow.definition.CompiledProcesses;
import lk.auradot.wms.workflow.forms.FormRef;
import lk.auradot.wms.workflow.forms.FormSubmissions;
import lk.auradot.wms.workflow.forms.NodeForms;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Completing a task: the task form goes through form-service (spec §5 steps 1-3), the comment is kept per task. */
@Service
public class TaskCompletion {

    private record Prepared(String definitionId, String nodeId, boolean engineWillAccept, Map<String, Object> form) {
    }

    private final WorkflowCommands commands;
    private final InstanceStore instances;
    private final CompiledProcesses processes;
    private final NodeForms nodeForms;
    private final FormSubmissions submissions;
    private final ProcessEngine engine;
    private final EventRecorder recorder;

    public TaskCompletion(WorkflowCommands commands, InstanceStore instances, CompiledProcesses processes,
                          NodeForms nodeForms, FormSubmissions submissions, ProcessEngine engine,
                          EventRecorder recorder) {
        this.commands = commands;
        this.instances = instances;
        this.processes = processes;
        this.nodeForms = nodeForms;
        this.submissions = submissions;
        this.engine = engine;
        this.recorder = recorder;
    }

    public String instanceOf(String taskId) {
        return instances.instanceOfTask(taskId)
                .orElseThrow(() -> ApiException.notFound("WF_TASK_NOT_FOUND", "Unknown task " + taskId));
    }

    public void complete(String taskId, String userId, String outcome, Map<String, Object> data, String comment) {
        Prepared prepared = commands.readInstance(instanceOf(taskId), loaded -> {
            Task task = task(loaded.state(), taskId);
            boolean accepted = accepts(processes.get(loaded.definitionId()), loaded.state(), taskId, userId, outcome);
            return new Prepared(loaded.definitionId(), task.nodeId(), accepted, formVariables(loaded.state()));
        });
        if (!prepared.engineWillAccept()) {
            completeRefused(taskId, userId, outcome);
            return;
        }
        // Only a completion the engine will accept stores a submission (spec §5).
        Map<String, Object> values =
                submissions.acceptTask(prepared.definitionId(), prepared.nodeId(), taskId, data, prepared.form(), userId);
        Map<String, Object> merged = new LinkedHashMap<>(values);
        if (comment != null && !comment.isBlank()) {
            merged.put("comment_" + taskId, comment.trim());
        }
        List<String> files = submissions.submittedFiles(prepared.definitionId(), prepared.nodeId(), values);
        commands.onTask(taskId, userId, (process, state) -> {
            engine.completeTask(process, state, taskId, userId, outcome, merged);
            recorder.filesSubmitted(state, files, userId);
        });
    }

    /**
     * The pre-check said the engine would refuse: let the engine explain why, without the person's data, which
     * nobody has validated. If the task became completable in the meantime (e.g. it was just claimed), the data
     * must go through the form first, so the caller is asked to try again.
     */
    public void completeRefused(String taskId, String userId, String outcome) {
        commands.onTask(taskId, userId, (process, state) -> {
            if (accepts(process, state, taskId, userId, outcome)) {
                throw ApiException.conflict("WF_CONCURRENT_UPDATE",
                        "This task changed while you were completing it; please try again");
            }
            engine.completeTask(process, state, taskId, userId, outcome, Map.of());
        });
    }

    /** Mirrors the engine's own checks for completing a task. */
    private static boolean accepts(CompiledProcess process, InstanceState state, String taskId, String userId,
                                   String outcome) {
        Task task = state.tasks().stream().filter(t -> t.id().equals(taskId)).findFirst().orElse(null);
        return task != null && state.status() == InstanceStatus.ACTIVE && task.isOpen() && userId.equals(task.assigneeId())
                && process.node(task.nodeId()) instanceof UserTaskNode node && node.outcomes().contains(outcome);
    }

    public Optional<TaskFormView> form(String taskId) {
        return commands.readInstance(instanceOf(taskId), loaded -> {
            Task task = task(loaded.state(), taskId);
            FormRef ref = nodeForms.of(loaded.definitionId()).get(task.nodeId());
            if (ref == null) {
                return Optional.empty();
            }
            Map<String, Object> values = new LinkedHashMap<>(formVariables(loaded.state()));
            ref.access().forEach((key, level) -> {
                if ("HIDDEN".equals(level)) {
                    values.remove(key);
                }
            });
            return Optional.of(new TaskFormView(ref.key(), ref.version(), ref.access(), values));
        });
    }

    private static Task task(InstanceState state, String taskId) {
        return state.tasks().stream().filter(t -> t.id().equals(taskId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("WF_TASK_NOT_FOUND", "Unknown task " + taskId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formVariables(InstanceState state) {
        return state.variables().get("form") instanceof Map<?, ?> form
                ? new LinkedHashMap<>((Map<String, Object>) form) : new LinkedHashMap<>();
    }
}
