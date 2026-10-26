package lk.auradot.wms.workflow.instance;

import java.time.Instant;
import java.util.Map;

public record HistoryEntry(String type, String actorId, Map<String, Object> data, Instant occurredAt) {
}
