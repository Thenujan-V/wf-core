package lk.auradot.wms.form.definition;

import java.time.Instant;
import java.util.Map;

public record FormDraft(String key, String name, Map<String, Object> definition, Instant updatedAt, String updatedBy) {
}
