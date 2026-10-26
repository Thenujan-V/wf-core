package lk.auradot.wms.document.document;

import java.time.Instant;

/** A row in a folder listing or search result. */
public record DocumentSummary(String id, String folderId, String name, String typeId, VersionView currentVersion,
                              String checkedOutBy, Instant checkedOutAt, Instant updatedAt, DocumentKind kind) {
}
