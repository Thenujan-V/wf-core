package lk.auradot.wms.form.submission;

import lk.auradot.wms.form.Json;
import lk.auradot.wms.form.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class SubmissionStore {

    private final JdbcClient jdbc;
    private final Json json;

    public SubmissionStore(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(Submission s) {
        jdbc.sql("""
                        INSERT INTO form_submission (id, form_key, form_version, data_json, submitted_by, submitted_at, context_ref)
                        VALUES (:id, :key, :version, :data, :by, :at, :context)""")
                .param("id", s.id()).param("key", s.formKey()).param("version", s.formVersion())
                .param("data", json.write(s.data())).param("by", s.submittedBy())
                .param("at", Times.toDb(s.submittedAt())).param("context", s.contextRef())
                .update();
    }

    public Optional<Submission> find(String id) {
        return jdbc.sql("SELECT * FROM form_submission WHERE id = :id").param("id", id)
                .query((rs, n) -> new Submission(rs.getString("id"), rs.getString("form_key"), rs.getInt("form_version"),
                        json.readMap(rs.getString("data_json")), rs.getString("submitted_by"),
                        Times.fromDb(rs.getObject("submitted_at", LocalDateTime.class)), rs.getString("context_ref")))
                .optional();
    }
}
