package lk.auradot.wms.document.document;

import java.time.Instant;

public record VersionView(String id, String label, int major, int minor, long size, String mimeType, String fileName,
                          String sha256, String createdBy, Instant createdAt, String comment) {

    static VersionView of(VersionRow v) {
        return new VersionView(v.id(), v.label(), v.major(), v.minor(), v.size(), v.mime(), v.fileName(), v.sha256(),
                v.createdBy(), v.createdAt(), v.comment());
    }
}
