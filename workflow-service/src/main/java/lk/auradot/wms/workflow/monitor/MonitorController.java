package lk.auradot.wms.workflow.monitor;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** Spec §9 admin/monitor. Dead jobs and the actions on a request live in JobController and InstanceController. */
@RestController
@PreAuthorize("hasRole('wms-admin')")
public class MonitorController {

    static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED");
    static final int MAX_SIZE = 100;

    private final MonitorQueries queries;

    public MonitorController(MonitorQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/admin/instances")
    public InstancePage instances(@RequestParam(required = false) String status,
                                  @RequestParam(required = false) String processKey,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        String wanted = text(status);
        if (wanted != null && !STATUSES.contains(wanted)) {
            throw ApiException.badRequest("WF_STATUS_INVALID", "Unknown request status " + wanted);
        }
        return queries.instances(wanted, text(processKey), text(q), Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
