package lk.auradot.wms.form.definition;

import java.time.Instant;
import java.util.Map;

public record StoredForm(String id, String key, int version, String name, String status, Map<String, Object> definition,
                         Instant publishedAt, String publishedBy) {
}
