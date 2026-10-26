package lk.auradot.wms.document.upload;

import java.time.Instant;
import java.util.Map;

/** Where and how the browser sends the file; the headers must be sent exactly as given. */
public record UploadTicket(String uploadId, String url, String method, Map<String, String> headers, Instant expiresAt) {
}
