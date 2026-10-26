package lk.auradot.wms.document.written;

import lk.auradot.wms.document.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class DraftStore {

    private final JdbcClient jdbc;

    public DraftStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<DraftRow> find(String documentId) {
        return jdbc.sql("SELECT document_id, content_html, revision, updated_by, updated_at FROM document_draft WHERE document_id = :d")
                .param("d", documentId)
                .query((rs, row) -> new DraftRow(rs.getString("document_id"), rs.getString("content_html"), rs.getInt("revision"),
                        rs.getString("updated_by"), Times.fromDb(rs.getObject("updated_at", LocalDateTime.class))))
                .optional();
    }

    public boolean exists(String documentId) {
        return jdbc.sql("SELECT COUNT(*) FROM document_draft WHERE document_id = :d").param("d", documentId)
                .query(Long.class).single() > 0;
    }

    public void insert(DraftRow draft) {
        jdbc.sql("""
                        INSERT INTO document_draft (document_id, content_html, revision, updated_by, updated_at)
                        VALUES (:d, :html, :revision, :by, :at)""")
                .param("d", draft.documentId()).param("html", draft.html()).param("revision", draft.revision())
                .param("by", draft.updatedBy()).param("at", Times.toDb(draft.updatedAt())).update();
    }

    /** Stores the text over the given revision; false when the stored text is no longer that revision. */
    public boolean update(String documentId, String html, int expectedRevision, String by, Instant at) {
        return jdbc.sql("""
                        UPDATE document_draft SET content_html = :html, revision = revision + 1, updated_by = :by, updated_at = :at
                        WHERE document_id = :d AND revision = :expected""")
                .param("html", html).param("by", by).param("at", Times.toDb(at)).param("d", documentId)
                .param("expected", expectedRevision).update() == 1;
    }

    public void delete(String documentId) {
        jdbc.sql("DELETE FROM document_draft WHERE document_id = :d").param("d", documentId).update();
    }
}
