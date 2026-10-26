package lk.auradot.wms.document.approval;

import java.time.Instant;
import java.util.List;

/** How a request stands or ended, as workflow-service reports it (its GET /instances/{id}/outcome). */
public record RequestOutcome(String id, String processKey, String processName, String businessKey, String status,
                             String endStatus, Instant endedAt, List<Step> steps) {

    public record Step(String name, String completedBy, String outcome, Instant completedAt) {
    }

    public RequestOutcome {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public boolean running() {
        return "ACTIVE".equals(status) || "SUSPENDED".equals(status);
    }

    /** What people call the request: its business key, or its id when it has none. */
    public String reference() {
        return businessKey == null || businessKey.isBlank() ? id : businessKey.strip();
    }
}
