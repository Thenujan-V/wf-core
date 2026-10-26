package lk.auradot.wms.document.document;

import java.time.Instant;

/** A presigned GET for one version; inline only for types the browser may show safely. */
public record ContentLink(String url, String fileName, String mimeType, boolean inline, Instant expiresAt) {
}
