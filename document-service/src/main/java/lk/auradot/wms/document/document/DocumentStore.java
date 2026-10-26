package lk.auradot.wms.document.document;

import lk.auradot.wms.document.Json;
import lk.auradot.wms.document.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class DocumentStore {

    private static final String DOCUMENT_COLUMNS = """
            id, folder_id, name, type_id, metadata_json, current_version_id, checked_out_by, checked_out_at,
            linked_instance_id, created_by, created_at, updated_at, kind""";
    private static final String VERSION_COLUMNS = """
            id, document_id, major, minor, s3_key, size, mime, sha256, file_name, created_by, created_at, comment""";

    private final JdbcClient jdbc;
    private final Json json;

    public DocumentStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(DocumentRow d, String searchText) {
        jdbc.sql("""
                        INSERT INTO document (id, folder_id, name, type_id, metadata_json, search_text, linked_instance_id,
                                              created_by, created_at, updated_at, kind)
                        VALUES (:id, :folder, :name, :type, :metadata, :search, :instance, :by, :at, :at, :kind)""")
                .param("id", d.id()).param("folder", d.folderId()).param("name", d.name()).param("type", d.typeId())
                .param("metadata", json.write(d.metadata())).param("search", searchText)
                .param("instance", d.linkedInstanceId()).param("by", d.createdBy())
                .param("at", Times.toDb(d.createdAt())).param("kind", d.kind().name()).update();
    }

    public void insertVersion(VersionRow v) {
        jdbc.sql("INSERT INTO document_version (" + VERSION_COLUMNS + """
                        ) VALUES (:id, :doc, :major, :minor, :key, :size, :mime, :sha, :file, :by, :at, :comment)""")
                .param("id", v.id()).param("doc", v.documentId()).param("major", v.major()).param("minor", v.minor())
                .param("key", v.s3Key()).param("size", v.size()).param("mime", v.mime()).param("sha", v.sha256())
                .param("file", v.fileName()).param("by", v.createdBy()).param("at", Times.toDb(v.createdAt()))
                .param("comment", v.comment()).update();
    }

    public void setCurrent(String documentId, String versionId, Instant now) {
        jdbc.sql("UPDATE document SET current_version_id = :v, updated_at = :at WHERE id = :id")
                .param("v", versionId).param("at", Times.toDb(now)).param("id", documentId).update();
    }

    public Optional<DocumentRow> find(String id) {
        return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + " FROM document WHERE id = :id").param("id", id)
                .query(this::mapDocument).optional();
    }

    /** Locks the row for the rest of the transaction: version numbers and check-outs change one at a time. */
    public Optional<DocumentRow> findForUpdate(String id) {
        return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + " FROM document WHERE id = :id FOR UPDATE").param("id", id)
                .query(this::mapDocument).optional();
    }

    public List<VersionRow> versions(String documentId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM document_version WHERE document_id = :d ORDER BY major DESC, minor DESC")
                .param("d", documentId).query(this::mapVersion).list();
    }

    public Optional<VersionRow> version(String documentId, String versionId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM document_version WHERE document_id = :d AND id = :v")
                .param("d", documentId).param("v", versionId).query(this::mapVersion).optional();
    }

    public Optional<VersionRow> latest(String documentId) {
        return versions(documentId).stream().findFirst();
    }

    public List<String> inFolder(String folderId) {
        return jdbc.sql("SELECT id FROM document WHERE folder_id = :f ORDER BY name").param("f", folderId).query(String.class).list();
    }

    /** The folder's documents, locked until the transaction ends. */
    public List<String> lockInFolder(String folderId) {
        return jdbc.sql("SELECT id FROM document WHERE folder_id = :f FOR UPDATE").param("f", folderId).query(String.class).list();
    }

    public boolean nameTaken(String folderId, String name) {
        return jdbc.sql("SELECT COUNT(*) FROM document WHERE folder_id = :f AND name = :n")
                .param("f", folderId).param("n", name).query(Long.class).single() > 0;
    }

    /** Summaries of these documents, in the order given. */
    public List<DocumentSummary> summaries(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, DocumentRow> documents = jdbc.sql("SELECT " + DOCUMENT_COLUMNS + " FROM document WHERE id IN (:ids)")
                .param("ids", ids).query(this::mapDocument).list().stream()
                .collect(Collectors.toMap(DocumentRow::id, Function.identity()));
        List<String> versionIds = documents.values().stream().map(DocumentRow::currentVersionId).filter(v -> v != null).toList();
        Map<String, VersionRow> versions = versionIds.isEmpty() ? Map.of()
                : jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM document_version WHERE id IN (:ids)").param("ids", versionIds)
                        .query(this::mapVersion).list().stream().collect(Collectors.toMap(VersionRow::id, Function.identity()));
        return ids.stream().map(documents::get).filter(d -> d != null)
                .map(d -> new DocumentSummary(d.id(), d.folderId(), d.name(), d.typeId(),
                        d.currentVersionId() == null ? null : VersionView.of(versions.get(d.currentVersionId())),
                        d.checkedOutBy(), d.checkedOutAt(), d.updatedAt(), d.kind()))
                .toList();
    }

    public void rename(String id, String name, String searchText, Instant now) {
        jdbc.sql("UPDATE document SET name = :name, search_text = :search, updated_at = :at WHERE id = :id")
                .param("name", name).param("search", searchText).param("at", Times.toDb(now)).param("id", id).update();
    }

    public void setMetadata(String id, String typeId, Map<String, Object> metadata, String searchText, Instant now) {
        jdbc.sql("""
                        UPDATE document SET type_id = :type, metadata_json = :metadata, search_text = :search, updated_at = :at
                        WHERE id = :id""")
                .param("type", typeId).param("metadata", json.write(metadata)).param("search", searchText)
                .param("at", Times.toDb(now)).param("id", id).update();
    }

    /** userId null releases the lock. */
    public void setCheckout(String id, String userId, Instant at) {
        jdbc.sql("UPDATE document SET checked_out_by = :user, checked_out_at = :at WHERE id = :id")
                .param("user", userId).param("at", userId == null ? null : Times.toDb(at)).param("id", id).update();
    }

    /** Deletes the document and its versions; returns the object keys to remove from storage. */
    public List<String> delete(String id) {
        List<String> keys = jdbc.sql("SELECT DISTINCT s3_key FROM document_version WHERE document_id = :id")
                .param("id", id).query(String.class).list();
        jdbc.sql("UPDATE document SET current_version_id = NULL WHERE id = :id").param("id", id).update();
        jdbc.sql("DELETE FROM document WHERE id = :id").param("id", id).update();
        return keys;
    }

    public void copyText(String fromVersionId, String toVersionId, Instant now) {
        jdbc.sql("""
                        INSERT INTO document_text (version_id, document_id, content, extracted_at)
                        SELECT :to, document_id, content, :at FROM document_text WHERE version_id = :from""")
                .param("to", toVersionId).param("from", fromVersionId).param("at", Times.toDb(now)).update();
    }

    private DocumentRow mapDocument(ResultSet rs, int row) throws SQLException {
        return new DocumentRow(rs.getString("id"), rs.getString("folder_id"), rs.getString("name"), rs.getString("type_id"),
                json.readMap(rs.getString("metadata_json")), rs.getString("current_version_id"), rs.getString("checked_out_by"),
                Times.fromDb(rs.getObject("checked_out_at", LocalDateTime.class)), rs.getString("linked_instance_id"),
                rs.getString("created_by"), Times.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                Times.fromDb(rs.getObject("updated_at", LocalDateTime.class)), DocumentKind.valueOf(rs.getString("kind")));
    }

    private VersionRow mapVersion(ResultSet rs, int row) throws SQLException {
        return new VersionRow(rs.getString("id"), rs.getString("document_id"), rs.getInt("major"), rs.getInt("minor"),
                rs.getString("s3_key"), rs.getLong("size"), rs.getString("mime"), rs.getString("sha256"),
                rs.getString("file_name"), rs.getString("created_by"),
                Times.fromDb(rs.getObject("created_at", LocalDateTime.class)), rs.getString("comment"));
    }

    public List<String> linkedTo(String instanceId) {
        return jdbc.sql("SELECT id FROM document WHERE linked_instance_id = :i ORDER BY created_at, id")
                .param("i", instanceId).query(String.class).list();
    }

    public void setLinkedInstance(String id, String instanceId, Instant now) {
        jdbc.sql("UPDATE document SET linked_instance_id = :i, updated_at = :at WHERE id = :id")
                .param("i", instanceId).param("at", Times.toDb(now)).param("id", id).update();
    }

    /** Moves a staged file into the request's folder and makes it the request's (spec §6). */
    public void moveToRequest(String id, String folderId, String instanceId, Instant now) {
        jdbc.sql("UPDATE document SET folder_id = :folder, linked_instance_id = :instance, updated_at = :at WHERE id = :id")
                .param("folder", folderId).param("instance", instanceId).param("at", Times.toDb(now)).param("id", id).update();
    }
}
