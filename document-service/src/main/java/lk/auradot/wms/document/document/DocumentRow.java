package lk.auradot.wms.document.document;

import java.time.Instant;
import java.util.Map;

public record DocumentRow(String id, String folderId, String name, String typeId, Map<String, Object> metadata,
                          String currentVersionId, String checkedOutBy, Instant checkedOutAt, String linkedInstanceId,
                          String createdBy, Instant createdAt, Instant updatedAt, DocumentKind kind) {
}
