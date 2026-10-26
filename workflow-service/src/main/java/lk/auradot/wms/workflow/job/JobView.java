package lk.auradot.wms.workflow.job;

import java.time.Instant;

/** A job as the admin monitor shows it, with the request it belongs to. */
public record JobView(String id, String instanceId, String type, String status, Instant dueAt, int attempts,
                      String lastError, String processKey, String businessKey) {
}
