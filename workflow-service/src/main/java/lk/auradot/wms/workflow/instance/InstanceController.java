package lk.auradot.wms.workflow.instance;

import jakarta.validation.Valid;
import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestController
public class InstanceController {

    private final WorkflowCommands commands;
    private final WorkflowQueries queries;
    private final ProcessEngine engine;
    private final UserContext users;
    private final VariableVisibility visibility;

    public InstanceController(WorkflowCommands commands, WorkflowQueries queries, ProcessEngine engine, UserContext users,
                              VariableVisibility visibility) {
        this.commands = commands;
        this.queries = queries;
        this.engine = engine;
        this.users = users;
        this.visibility = visibility;
    }

    @PostMapping("/instances")
    public ResponseEntity<InstanceSummary> start(@Valid @RequestBody StartRequest request, Authentication auth) {
        WorkflowCommands.Started started = commands.startOnce(request.processKey(), users.userId(auth),
                request.data() == null ? Map.of() : request.data(), request.businessKey());
        InstanceSummary summary = queries.summary(started.id()).orElseThrow();
        // a replayed start (same business key) answers 200 with the request it already created
        return started.created()
                ? ResponseEntity.created(URI.create("/instances/" + started.id())).body(summary)
                : ResponseEntity.ok(summary);
    }

    /** For other services: find the request they started under a business key (e.g. after a timed-out start). */
    @GetMapping("/instances/lookup")
    @PreAuthorize("hasAnyRole('wms-service', 'wms-admin')")
    public InstanceSummary lookup(@RequestParam String businessKey) {
        return queries.summaryByBusinessKey(businessKey).orElseThrow(InstanceController::notFound);
    }

    @GetMapping("/instances")
    public List<InstanceSummary> mine(Authentication auth) {
        return queries.startedBy(users.userId(auth));
    }

    @GetMapping("/instances/{id}")
    public InstanceDetail detail(@PathVariable String id, Authentication auth) {
        boolean admin = users.isAdmin(auth);
        String userId = users.userId(auth);
        List<String> pools = admin ? List.of() : users.candidates(auth);
        if (!admin && !queries.canView(id, userId, pools)) {
            throw notFound();
        }
        InstanceDetail detail = queries.detail(id).orElseThrow(InstanceController::notFound);
        return new InstanceDetail(detail.instance(),
                visibility.forViewer(detail, queries.definitionId(id), userId, admin, pools), detail.tasks());
    }

    @GetMapping("/instances/{id}/history")
    public List<HistoryEntry> history(@PathVariable String id, Authentication auth) {
        requireView(id, auth);
        return queries.history(id);
    }


    /**
     * For other services: how the request ended and who decided each step. Not for the people of the request, who have
     * GET /instances/{id}; a service asks about requests it was told of, whoever they belong to.
     */
    @GetMapping("/instances/{id}/outcome")
    @PreAuthorize("hasAnyRole('wms-service', 'wms-admin')")
    public InstanceOutcome outcome(@PathVariable String id) {
        return queries.outcome(id).orElseThrow(InstanceController::notFound);
    }

    /**
     * Spec §4.5 access-check, answered for the caller (document-service forwards the person's own token): whether they
     * may see this request, and whether they may still add documents to it. Unseen requests are 404, as elsewhere.
     */
    @GetMapping("/instances/{id}/access-check")
    public InstanceAccess access(@PathVariable String id, Authentication auth) {
        requireView(id, auth);
        InstanceSummary summary = queries.summary(id).orElseThrow(InstanceController::notFound);
        boolean attach = "ACTIVE".equals(summary.status())
                && (users.isAdmin(auth) || queries.canAttach(id, users.userId(auth)));
        return new InstanceAccess(id, summary.processKey(), summary.status(), true, attach);
    }

    /** Administrators, and services stopping requests they started (e.g. a withdrawn document review). */
    @PostMapping("/instances/{id}/cancel")
    @PreAuthorize("hasAnyRole('wms-admin', 'wms-service')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable String id, @Valid @RequestBody(required = false) CancelRequest request,
                       Authentication auth) {
        String actor = users.userId(auth);
        String reason = request == null ? null : request.reason();
        commands.onInstance(id, actor, (p, s) -> engine.cancel(p, s, actor, reason));
    }

    @PostMapping("/instances/{id}/suspend")
    @PreAuthorize("hasRole('wms-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void suspend(@PathVariable String id, Authentication auth) {
        String admin = users.userId(auth);
        commands.onInstance(id, admin, (p, s) -> engine.suspend(p, s, admin));
    }

    @PostMapping("/instances/{id}/resume")
    @PreAuthorize("hasRole('wms-admin')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resume(@PathVariable String id, Authentication auth) {
        String admin = users.userId(auth);
        commands.onInstance(id, admin, (p, s) -> engine.resume(p, s, admin));
    }

    /** Requests the caller may not see look exactly like requests that do not exist. */
    private void requireView(String id, Authentication auth) {
        if (!users.isAdmin(auth) && !queries.canView(id, users.userId(auth), users.candidates(auth))) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return ApiException.notFound("WF_INSTANCE_NOT_FOUND", "Request not found");
    }
}
