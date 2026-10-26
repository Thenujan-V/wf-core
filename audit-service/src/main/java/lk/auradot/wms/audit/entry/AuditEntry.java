package lk.auradot.wms.audit.entry;

import java.time.Instant;
import java.util.Map;

/** One recorded event (spec §8): actor, action, entity type/id, data, correlation id, at. */
public record AuditEntry(long id, String action, String actorId, String entityType, String entityId,
                         Map<String, Object> data, String correlationId, Instant occurredAt) {
}
