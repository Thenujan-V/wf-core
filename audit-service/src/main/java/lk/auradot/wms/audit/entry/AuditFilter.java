package lk.auradot.wms.audit.entry;

import java.time.Instant;

/** Null fields do not filter. {@code from} is inclusive, {@code to} exclusive. */
public record AuditFilter(String actor, String entityType, String entityId, String action, Instant from, Instant to,
                          int page, int size) {
}
