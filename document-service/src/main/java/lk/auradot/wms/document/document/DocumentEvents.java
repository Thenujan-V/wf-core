package lk.auradot.wms.document.document;

import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.common.messaging.Outbox;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DocumentEvents {

    private DocumentEvents() {
    }

    /** Triggers text extraction (spec §6) and, later, notifications and audit. */
    public static void versionUploaded(Outbox outbox, DocumentRow document, VersionRow version, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", document.id());
        payload.put("folderId", document.folderId());
        payload.put("versionId", version.id());
        payload.put("label", version.label());
        payload.put("s3Key", version.s3Key());
        payload.put("mimeType", version.mime());
        payload.put("fileName", version.fileName());
        payload.put("size", version.size());
        payload.put("actorId", version.createdBy());
        outbox.add(MessagingConfig.VERSION_UPLOADED, payload, now);
    }
}
