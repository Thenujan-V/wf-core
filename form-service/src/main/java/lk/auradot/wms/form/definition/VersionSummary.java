package lk.auradot.wms.form.definition;

import java.time.Instant;

public record VersionSummary(int version, String status, Instant publishedAt) {
}
