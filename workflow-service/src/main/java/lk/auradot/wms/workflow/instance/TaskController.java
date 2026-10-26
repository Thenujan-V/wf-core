package lk.auradot.wms.workflow.instance;

import jakarta.validation.Valid;
import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class TaskController {

    private final WorkflowCommands commands;
    private final WorkflowQueries queries;
    private final ProcessEngine engine;
    private final UserContext users;
    private final TaskCompletion completion;
    private final VariableVisibility visibility;

    public TaskController(WorkflowCommands commands, WorkflowQueries queries, ProcessEngine engine, UserContext users,
                          TaskCompletion completion, VariableVisibility visibility) {
        this.completion = completion;
        this.visibility = visibility;
        this.commands = commands;
        this.queries = queries;
        this.engine = engine;
        this.users = users;
    }

    @GetMapping("/tasks")
    public List<TaskView> tasks(@RequestParam(defaultValue = "mine") String view, Authentication auth) {
        return switch (view) {
            case "mine" -> queries.myTasks(users.userId(auth));
            case "group" -> queries.claimableTasks(users.candidates(auth));
            case "all" -> {
                if (!users.isAdmin(auth)) {
                    throw new AccessDeniedException("Only administrators can list every task");
                }
                yield queries.allOpenTasks();
            }
            default -> throw ApiException.badRequest("WF_VIEW_INVALID", "Use view=mine, group or all");
        };
    }

    @PostMapping("/tasks/{id}/claim")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void claim(@PathVariable String id, Authentication auth) {
        String user = users.userId(auth);
        commands.onTask(id, user, (p, s) -> engine.claimTask(p, s, id, user));
    }

    @PostMapping("/tasks/{id}/unclaim")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unclaim(@PathVariable String id, Authentication auth) {
        String user = users.userId(auth);
        commands.onTask(id, user, (p, s) -> engine.unclaimTask(p, s, id, user));
    }

    @PostMapping("/tasks/{id}/delegate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delegate(@PathVariable String id, @Valid @RequestBody AssignRequest request, Authentication auth) {
        String user = users.userId(auth);
        commands.onTask(id, user, (p, s) -> engine.delegateTask(p, s, id, user, request.toUserId()));
    }

    @PostMapping("/tasks/{id}/reassign")
    @PreAuthorize("hasRole('wms-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reassign(@PathVariable String id, @Valid @RequestBody AssignRequest request, Authentication auth) {
        String admin = users.userId(auth);
        commands.onTask(id, admin, (p, s) -> engine.reassignTask(p, s, id, request.toUserId(), admin));
    }

    @PostMapping("/tasks/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void complete(@PathVariable String id, @Valid @RequestBody CompleteRequest request, Authentication auth) {
        Map<String, Object> data = request.data() == null ? Map.of() : request.data();
        completion.complete(id, users.userId(auth), request.outcome(), data, request.comment());
    }

    /**
     * The task's form for people who may see the request; others learn nothing about the task. Fields the reader's own
     * steps all hide stay hidden here too (spec §5), whichever step's form they open.
     */
    @GetMapping("/tasks/{id}/form")
    public TaskFormView form(@PathVariable String id, Authentication auth) {
        String instanceId = completion.instanceOf(id);
        boolean admin = users.isAdmin(auth);
        String userId = users.userId(auth);
        List<String> pools = admin ? List.of() : users.candidates(auth);
        if (!admin && !queries.canView(instanceId, userId, pools)) {
            throw ApiException.notFound("WF_TASK_NOT_FOUND", "Unknown task " + id);
        }
        TaskFormView view = completion.form(id)
                .orElseThrow(() -> ApiException.notFound("WF_TASK_FORM_NOT_FOUND", "This task has no form"));
        String initiator = queries.summary(instanceId).map(InstanceSummary::initiatorId).orElse(null);
        Set<String> hidden = visibility.hiddenFor(instanceId, queries.definitionId(instanceId), initiator, userId,
                admin, pools);
        if (hidden.isEmpty()) {
            return view;
        }
        Map<String, Object> values = new LinkedHashMap<>(view.values());
        values.keySet().removeAll(hidden);
        return new TaskFormView(view.formKey(), view.formVersion(), view.access(), values);
    }
}
