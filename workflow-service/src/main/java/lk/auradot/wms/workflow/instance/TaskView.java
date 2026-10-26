package lk.auradot.wms.workflow.instance;

import java.time.Instant;
import java.util.List;

/** {@code formKey}/{@code formVersion}: the form this task shows (null when it has none). */
public record TaskView(String id, String instanceId, String processKey, String businessKey, String initiatorId,
                       String nodeId, String name, String status, String assigneeId, List<String> candidates,
                       List<String> outcomes, String unassignedReason, Instant createdAt, Instant dueAt,
                       String formKey, Integer formVersion) {
}
