package lk.auradot.wms.document.written;

import lk.auradot.wms.document.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class SubmissionStore {

    private static final String COLUMNS = """
            id, document_id, instance_id, content_html, review_s3_key, review_size, review_sha256, status, end_status,
            submitted_by, submitted_at, decided_at, version_id""";

    private final JdbcClient jdbc;

    public SubmissionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SubmissionRow s) {
        jdbc.sql("INSERT INTO document_submission (" + COLUMNS + """
                        ) VALUES (:id, :doc, :instance, :html, :key, :size, :sha, :status, :end, :by, :at, :decided, :version)""")
                .param("id", s.id()).param("doc", s.documentId()).param("instance", s.instanceId()).param("html", s.html())
                .param("key", s.reviewKey()).param("size", s.reviewSize()).param("sha", s.reviewSha256())
                .param("status", s.status()).param("end", s.endStatus()).param("by", s.submittedBy())
                .param("at", Times.toDb(s.submittedAt()))
                .param("decided", s.decidedAt() == null ? null : Times.toDb(s.decidedAt())).param("version", s.versionId())
                .update();
    }

    /** The round that is not closed yet, if any. */
    public Optional<SubmissionRow> openOf(String documentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM document_submission WHERE document_id = :d AND status IN ('PENDING', 'ENDED')")
                .param("d", documentId).query(this::map).optional();
    }

    public Optional<SubmissionRow> openForInstance(String instanceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM document_submission WHERE instance_id = :i AND status IN ('PENDING', 'ENDED')")
                .param("i", instanceId).query(this::map).optional();
    }

    public Optional<SubmissionRow> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM document_submission WHERE id = :id").param("id", id).query(this::map).optional();
    }

    /** The request is over; what it decided is settled next. False when the round is no longer waiting for that. */
    public boolean markEnded(String id) {
        return jdbc.sql("UPDATE document_submission SET status = 'ENDED' WHERE id = :id AND status = 'PENDING'")
                .param("id", id).update() == 1;
    }

    /** Closes an open round; false when someone else already closed it. */
    public boolean decide(String id, String status, String endStatus, Instant at) {
        return jdbc.sql("""
                        UPDATE document_submission SET status = :status, end_status = :end, decided_at = :at
                        WHERE id = :id AND status IN ('PENDING', 'ENDED')""")
                .param("status", status).param("end", endStatus).param("at", Times.toDb(at)).param("id", id).update() == 1;
    }

    /** Closes an open round as approved, naming the version its PDF became; false when it was already closed. */
    public boolean approve(String id, String endStatus, String versionId, Instant at) {
        return jdbc.sql("""
                        UPDATE document_submission SET status = 'APPROVED', end_status = :end, decided_at = :at, version_id = :version
                        WHERE id = :id AND status IN ('PENDING', 'ENDED')""")
                .param("end", endStatus).param("at", Times.toDb(at)).param("version", versionId).param("id", id).update() == 1;
    }

    /** Rounds whose request is over and whose outcome is not settled yet, oldest first. */
    public List<SubmissionRow> ended() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM document_submission WHERE status = 'ENDED' ORDER BY submitted_at, id")
                .query(this::map).list();
    }

    /** The review PDFs of every round of this document, for removing them from storage with it. */
    public List<String> reviewKeysOf(String documentId) {
        return jdbc.sql("SELECT review_s3_key FROM document_submission WHERE document_id = :d").param("d", documentId)
                .query(String.class).list();
    }

    /**
     * Rounds still waiting to hear that their request ended, sent before the given time and not checked since it.
     * Oldest first.
     */
    public List<SubmissionRow> stalePending(Instant before) {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM document_submission
                        WHERE status = 'PENDING' AND submitted_at < :before AND (checked_at IS NULL OR checked_at < :before)
                        ORDER BY submitted_at, id""")
                .param("before", Times.toDb(before)).query(this::map).list();
    }

    public void touch(String id, Instant at) {
        jdbc.sql("UPDATE document_submission SET checked_at = :at WHERE id = :id").param("at", Times.toDb(at)).param("id", id).update();
    }

    private SubmissionRow map(ResultSet rs, int row) throws SQLException {
        return new SubmissionRow(rs.getString("id"), rs.getString("document_id"), rs.getString("instance_id"),
                rs.getString("content_html"), rs.getString("review_s3_key"), rs.getLong("review_size"),
                rs.getString("review_sha256"), rs.getString("status"), rs.getString("end_status"), rs.getString("submitted_by"),
                Times.fromDb(rs.getObject("submitted_at", LocalDateTime.class)),
                Times.fromDb(rs.getObject("decided_at", LocalDateTime.class)), rs.getString("version_id"));
    }
}
