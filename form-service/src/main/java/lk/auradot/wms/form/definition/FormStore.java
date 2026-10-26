package lk.auradot.wms.form.definition;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.form.Json;
import lk.auradot.wms.form.Times;
import lk.auradot.wms.form.model.DefinitionChecks;
import lk.auradot.wms.form.model.FieldError;
import lk.auradot.wms.form.model.FormJson;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Drafts and immutable published versions of form definitions (spec §5). */
@Repository
public class FormStore {

    private static final String COLUMNS = "id, form_key, version, name, status, definition_json, published_at, published_by";

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;

    public FormStore(JdbcClient jdbc, Json json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void saveDraft(String key, String name, Map<String, Object> definition, String userId) {
        jdbc.sql("""
                        INSERT INTO form_draft (form_key, name, definition_json, updated_at, updated_by)
                        VALUES (:key, :name, :definition, :at, :by) AS new
                        ON DUPLICATE KEY UPDATE name = new.name, definition_json = new.definition_json,
                            updated_at = new.updated_at, updated_by = new.updated_by""")
                .param("key", key).param("name", name).param("definition", json.write(definition))
                .param("at", Times.toDb(clock.instant())).param("by", userId)
                .update();
    }

    public Optional<FormDraft> draft(String key) {
        return jdbc.sql("SELECT form_key, name, definition_json, updated_at, updated_by FROM form_draft WHERE form_key = :key")
                .param("key", key)
                .query((rs, n) -> new FormDraft(rs.getString("form_key"), rs.getString("name"),
                        json.readMap(rs.getString("definition_json")),
                        Times.fromDb(rs.getObject("updated_at", LocalDateTime.class)), rs.getString("updated_by")))
                .optional();
    }

    public List<FieldError> validateDraft(String key) {
        return check(requireDraft(key).definition());
    }

    @Transactional
    public StoredForm publish(String key, String userId) {
        FormDraft draft = requireDraft(key);
        List<FieldError> errors = check(draft.definition());
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("FORM_DEFINITION_INVALID",
                    "The form has " + errors.size() + " problem(s) to fix before publishing").with("errors", errors);
        }
        int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM form_definition WHERE form_key = :key")
                .param("key", key).query(Integer.class).single();
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try {
            jdbc.sql("""
                            INSERT INTO form_definition (id, form_key, version, name, status, definition_json,
                                published_at, published_by)
                            VALUES (:id, :key, :version, :name, 'PUBLISHED', :definition, :at, :by)""")
                    .param("id", id).param("key", key).param("version", version).param("name", draft.name())
                    .param("definition", json.write(draft.definition())).param("at", Times.toDb(now)).param("by", userId)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("FORM_PUBLISH_CONFLICT", "Someone else published this form at the same time; try again");
        }
        return version(key, version).orElseThrow();
    }

    /** Retired versions stay readable and submittable: running requests pin their form version. */
    public void retire(String key) {
        int changed = jdbc.sql("UPDATE form_definition SET status = 'RETIRED' WHERE form_key = :key")
                .param("key", key).update();
        if (changed == 0) {
            throw ApiException.notFound("FORM_NOT_FOUND", "No published form '" + key + "'");
        }
    }

    public Optional<StoredForm> version(String key, int version) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM form_definition WHERE form_key = :key AND version = :version")
                .param("key", key).param("version", version).query(this::mapForm).optional();
    }

    public List<VersionSummary> versions(String key) {
        return jdbc.sql("SELECT version, status, published_at FROM form_definition WHERE form_key = :key ORDER BY version DESC")
                .param("key", key)
                .query((rs, n) -> new VersionSummary(rs.getInt(1), rs.getString(2),
                        Times.fromDb(rs.getObject(3, LocalDateTime.class))))
                .list();
    }

    public List<FormSummary> list() {
        Map<String, FormSummary> byKey = new TreeMap<>();
        jdbc.sql("""
                        SELECT d.form_key, d.name, d.version, d.status FROM form_definition d
                        WHERE d.version = (SELECT MAX(x.version) FROM form_definition x WHERE x.form_key = d.form_key)""")
                .query(rs -> {
                    byKey.put(rs.getString(1), new FormSummary(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getString(4), false, null));
                });
        jdbc.sql("SELECT form_key, name, updated_at FROM form_draft").query(rs -> {
            String key = rs.getString(1);
            Instant updatedAt = Times.fromDb(rs.getObject(3, LocalDateTime.class));
            FormSummary published = byKey.get(key);
            byKey.put(key, published == null
                    ? new FormSummary(key, rs.getString(2), null, "DRAFT", true, updatedAt)
                    : new FormSummary(key, published.name(), published.latestVersion(), published.status(), true, updatedAt));
        });
        return new ArrayList<>(byKey.values());
    }

    /** JSON problems first; only a well-formed definition goes through the publish checks. */
    static List<FieldError> check(Map<String, Object> definition) {
        FormJson.Result result = FormJson.read(definition);
        return result.ok() ? DefinitionChecks.check(result.definition()) : result.errors();
    }

    private FormDraft requireDraft(String key) {
        return draft(key).orElseThrow(
                () -> ApiException.notFound("FORM_DRAFT_NOT_FOUND", "There is no draft for form '" + key + "'"));
    }

    private StoredForm mapForm(ResultSet rs, int row) throws SQLException {
        return new StoredForm(rs.getString("id"), rs.getString("form_key"), rs.getInt("version"), rs.getString("name"),
                rs.getString("status"), json.readMap(rs.getString("definition_json")),
                Times.fromDb(rs.getObject("published_at", LocalDateTime.class)), rs.getString("published_by"));
    }
}
