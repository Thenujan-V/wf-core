package lk.auradot.wms.document.document;

import java.time.Instant;

public record VersionRow(String id, String documentId, int major, int minor, String s3Key, long size, String mime,
                         String sha256, String fileName, String createdBy, Instant createdAt, String comment) {

    public String label() {
        return major + "." + minor;
    }
}
