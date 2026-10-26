package lk.auradot.wms.workflow.definition;

import java.time.Instant;
import java.util.Map;

public record StoredDefinition(String id, String key, int version, String name, String status,
                               Map<String, Object> graph, Instant publishedAt, String publishedBy) {
}
