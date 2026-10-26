package lk.auradot.wms.workflow.job;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.runtime.JobStatus;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.workflow.instance.UserContext;
import lk.auradot.wms.workflow.instance.WorkflowCommands;
import lk.auradot.wms.workflow.instance.WorkflowQueries;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Admin monitor: failed (dead) jobs and manual retry (spec §4.4). */
@RestController
@PreAuthorize("hasRole('wms-admin')")
public class JobController {

    private final WorkflowQueries queries;
    private final WorkflowCommands commands;
    private final ProcessEngine engine;
    private final UserContext users;

    public JobController(WorkflowQueries queries, WorkflowCommands commands, ProcessEngine engine, UserContext users) {
        this.queries = queries;
        this.commands = commands;
        this.engine = engine;
        this.users = users;
    }

    @GetMapping("/jobs")
    public List<JobView> jobs(@RequestParam(defaultValue = "DEAD") String status) {
        try {
            return queries.jobs(JobStatus.valueOf(status));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("WF_STATUS_INVALID", "Unknown job status " + status);
        }
    }

    @PostMapping("/jobs/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable String id, Authentication auth) {
        String admin = users.userId(auth);
        commands.onJob(id, admin, (p, s) -> engine.retryJob(p, s, id, admin));
    }
}
