package lk.auradot.wms.document.written;

import java.time.Instant;

public record DraftRow(String documentId, String html, int revision, String updatedBy, Instant updatedAt) {
}
