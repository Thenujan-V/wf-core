package lk.auradot.wms.document.upload;

import lk.auradot.wms.document.Json;
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
public class UploadStore {

    private static final String COLUMNS = """
            id, s3_key, file_name, size, sha256, mime, folder_id, document_id, name, type_id, metadata_json, major_version,
            comment, created_by, created_at, expires_at, confirmed_at, instance_id""";

    private final JdbcClient jdbc;
    private final Json json;

    public UploadStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(UploadRow u) {
        jdbc.sql("INSERT INTO upload (" + COLUMNS + """
                        ) VALUES (:id, :key, :file, :size, :sha, :mime, :folder, :doc, :name, :type, :metadata, :major,
                                  :comment, :by, :at, :expires, NULL, :instance)""")
                .param("id", u.id()).param("key", u.s3Key()).param("file", u.fileName()).param("size", u.size())
                .param("sha", u.sha256()).param("mime", u.mime()).param("folder", u.folderId()).param("doc", u.documentId())
                .param("name", u.name()).param("type", u.typeId())
                .param("metadata", u.metadata() == null ? null : json.write(u.metadata())).param("major", u.majorVersion())
                .param("comment", u.comment()).param("by", u.createdBy()).param("at", Times.toDb(u.createdAt()))
                .param("expires", Times.toDb(u.expiresAt())).param("instance", u.instanceId()).update();
    }

    public Optional<UploadRow> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM upload WHERE id = :id").param("id", id).query(this::map).optional();
    }

    /** False when another request confirmed it first. */
    public boolean markConfirmed(String id, Instant now) {
        return jdbc.sql("UPDATE upload SET confirmed_at = :at WHERE id = :id AND confirmed_at IS NULL")
                .param("at", Times.toDb(now)).param("id", id).update() == 1;
    }

    public List<UploadRow> unconfirmedBefore(Instant cutoff) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM upload WHERE confirmed_at IS NULL AND created_at < :cutoff")
                .param("cutoff", Times.toDb(cutoff)).query(this::map).list();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM upload WHERE id = :id").param("id", id).update();
    }

    public void deleteConfirmedBefore(Instant cutoff) {
        jdbc.sql("DELETE FROM upload WHERE confirmed_at IS NOT NULL AND created_at < :cutoff").param("cutoff", Times.toDb(cutoff)).update();
    }

    private UploadRow map(ResultSet rs, int row) throws SQLException {
        String metadata = rs.getString("metadata_json");
        return new UploadRow(rs.getString("id"), rs.getString("s3_key"), rs.getString("file_name"), rs.getLong("size"),
                rs.getString("sha256"), rs.getString("mime"), rs.getString("folder_id"), rs.getString("document_id"),
                rs.getString("name"), rs.getString("type_id"), metadata == null ? null : json.readMap(metadata),
                rs.getBoolean("major_version"), rs.getString("comment"), rs.getString("created_by"),
                Times.fromDb(rs.getObject("created_at", LocalDateTime.class)),
                Times.fromDb(rs.getObject("expires_at", LocalDateTime.class)),
                Times.fromDb(rs.getObject("confirmed_at", LocalDateTime.class)), rs.getString("instance_id"));
    }
}
