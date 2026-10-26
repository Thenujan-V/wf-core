package lk.auradot.wms.document.written;

import java.time.Instant;

/** One round of approval of a written document. */
public record SubmissionRow(String id, String documentId, String instanceId, String html, String reviewKey, long reviewSize,
                            String reviewSha256, String status, String endStatus, String submittedBy, Instant submittedAt,
                            Instant decidedAt, String versionId) {

    public static final String PENDING = "PENDING";
    /** The request is over and its outcome is being settled; the document is still in approval. */
    public static final String ENDED = "ENDED";
    public static final String APPROVED = "APPROVED";
    public static final String NOT_APPROVED = "NOT_APPROVED";
}
