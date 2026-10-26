package lk.auradot.wms.document.written;

import java.time.Instant;

/** The text being worked on, and whether the caller may change it right now. */
public record DraftView(String html, int revision, String updatedBy, Instant updatedAt, boolean editable) {
}
