package lk.auradot.wms.document.upload;

import java.time.Instant;
import java.util.Map;

/** A presigned upload. folderId set: a new document; documentId set: a new version of it; instanceId set: the new document is attached to that request. */
public record UploadRow(String id, String s3Key, String fileName, long size, String sha256, String mime, String folderId,
                        String documentId, String name, String typeId, Map<String, Object> metadata, boolean majorVersion,
                        String comment, String createdBy, Instant createdAt, Instant expiresAt, Instant confirmedAt,
                        String instanceId) {
}
