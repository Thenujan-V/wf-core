package lk.auradot.wms.workflow.definition;

import java.time.Instant;
import java.util.Map;

public record Draft(String key, String name, Map<String, Object> graph, Instant updatedAt, String updatedBy) {
}
