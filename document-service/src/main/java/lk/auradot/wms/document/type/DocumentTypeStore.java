package lk.auradot.wms.document.type;

import lk.auradot.wms.document.Times;
import lk.auradot.wms.document.forms.FormRef;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class DocumentTypeStore {

    private final JdbcClient jdbc;

    public DocumentTypeStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DocumentType> list() {
        return jdbc.sql("SELECT id, name, form_key, form_version FROM document_type ORDER BY name").query(this::map).list();
    }

    public Optional<DocumentType> find(String id) {
        return jdbc.sql("SELECT id, name, form_key, form_version FROM document_type WHERE id = :id")
                .param("id", id).query(this::map).optional();
    }

    public void insert(String id, String name, FormRef form, Instant now) {
        jdbc.sql("""
                        INSERT INTO document_type (id, name, form_key, form_version, created_at)
                        VALUES (:id, :name, :key, :version, :at)""")
                .param("id", id).param("name", name).param("key", form == null ? null : form.key())
                .param("version", form == null ? null : form.version()).param("at", Times.toDb(now)).update();
    }

    public boolean update(String id, String name, FormRef form) {
        return jdbc.sql("UPDATE document_type SET name = :name, form_key = :key, form_version = :version WHERE id = :id")
                .param("id", id).param("name", name).param("key", form == null ? null : form.key())
                .param("version", form == null ? null : form.version()).update() > 0;
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM document_type WHERE id = :id").param("id", id).update();
    }

    public boolean inUse(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM document WHERE type_id = :id").param("id", id).query(Long.class).single() > 0;
    }

    private DocumentType map(ResultSet rs, int row) throws SQLException {
        String key = rs.getString("form_key");
        return new DocumentType(rs.getString("id"), rs.getString("name"), key == null ? null : new FormRef(key, rs.getInt("form_version")));
    }
}
