package lk.auradot.wms.document.text;

import lk.auradot.wms.document.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public class DocumentTextStore {

    /** A current version whose text has not been extracted. */
    public record Pending(String documentId, String versionId, String s3Key) {
    }

    private final JdbcClient jdbc;

    public DocumentTextStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String versionId) {
        return jdbc.sql("SELECT COUNT(*) FROM document_text WHERE version_id = :v").param("v", versionId).query(Long.class).single() > 0;
    }

    /** Stores the text unless the version was deleted meanwhile. */
    public void saveIfVersionExists(String versionId, String documentId, String text, Instant now) {
        jdbc.sql("""
                        INSERT INTO document_text (version_id, document_id, content, extracted_at)
                        SELECT id, :doc, :content, :at FROM document_version WHERE id = :v
                        ON DUPLICATE KEY UPDATE content = :content, extracted_at = :at""")
                .param("v", versionId).param("doc", documentId).param("content", text).param("at", Times.toDb(now)).update();
    }

    /** Current versions created before the cutoff that have no text yet, oldest first. */
    public List<Pending> missingCurrent(Instant createdBefore, int limit) {
        return jdbc.sql("""
                        SELECT d.id, v.id AS version_id, v.s3_key FROM document d
                        JOIN document_version v ON v.id = d.current_version_id
                        LEFT JOIN document_text t ON t.version_id = v.id
                        WHERE t.version_id IS NULL AND v.created_at < :before
                        ORDER BY v.created_at LIMIT :limit""")
                .param("before", Times.toDb(createdBefore)).param("limit", limit)
                .query((rs, n) -> new Pending(rs.getString("id"), rs.getString("version_id"), rs.getString("s3_key"))).list();
    }
}
