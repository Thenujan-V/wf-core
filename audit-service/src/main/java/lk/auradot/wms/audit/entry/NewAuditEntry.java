package lk.auradot.wms.audit.entry;

import java.time.Instant;
import java.util.Map;

/** An event as it will be recorded (spec §8): actor, action, entity type/id, data, correlation id, at. */
public record NewAuditEntry(String messageId, String action, String actorId, String entityType, String entityId,
                            Map<String, Object> data, String correlationId, Instant occurredAt) {
}
