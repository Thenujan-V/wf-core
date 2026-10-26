package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.definition.DefinitionValidator;
import lk.auradot.wms.engine.definition.ValidationError;
import lk.auradot.wms.workflow.Json;
import lk.auradot.wms.workflow.Times;
import lk.auradot.wms.workflow.forms.FormReferenceChecks;
import lk.auradot.wms.workflow.forms.ProcessForms;
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

/** Drafts and immutable published versions of process definitions (spec §4.1). */
@Repository
public class DefinitionStore {

    private static final String COLUMNS =
            "id, process_key, version, name, status, graph_json, published_at, published_by";

    private final JdbcClient jdbc;
    private final Json json;
    private final Clock clock;
    private final FormReferenceChecks formChecks;

    public DefinitionStore(JdbcClient jdbc, Json json, Clock clock, FormReferenceChecks formChecks) {
        this.formChecks = formChecks;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public void saveDraft(String key, String name, Map<String, Object> graph, String userId) {
        jdbc.sql("""
                        INSERT INTO process_draft (process_key, name, graph_json, updated_at, updated_by)
                        VALUES (:key, :name, :graph, :at, :by) AS new
                        ON DUPLICATE KEY UPDATE name = new.name, graph_json = new.graph_json,
                            updated_at = new.updated_at, updated_by = new.updated_by""")
                .param("key", key).param("name", name).param("graph", json.write(graph))
                .param("at", Times.toDb(clock.instant())).param("by", userId)
                .update();
    }

    public Optional<Draft> draft(String key) {
        return jdbc.sql("SELECT process_key, name, graph_json, updated_at, updated_by FROM process_draft WHERE process_key = :key")
                .param("key", key)
                .query((rs, n) -> new Draft(rs.getString("process_key"), rs.getString("name"),
                        json.readMap(rs.getString("graph_json")),
                        Times.fromDb(rs.getObject("updated_at", LocalDateTime.class)), rs.getString("updated_by")))
                .optional();
    }

    public List<ValidationError> validateDraft(String key) {
        Draft draft = requireDraft(key);
        return check(key, 0, draft.name(), draft.graph());
    }

    @Transactional
    public StoredDefinition publish(String key, String userId) {
        Draft draft = requireDraft(key);
        int version = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM process_definition WHERE process_key = :key")
                .param("key", key).query(Integer.class).single();
        List<ValidationError> errors = check(key, version, draft.name(), draft.graph());
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("WF_DEFINITION_INVALID",
                    "The process has " + errors.size() + " problem(s) to fix before publishing").with("errors", errors);
        }
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try {
            jdbc.sql("""
                            INSERT INTO process_definition (id, process_key, version, name, status, graph_json,
                                published_at, published_by)
                            VALUES (:id, :key, :version, :name, 'PUBLISHED', :graph, :at, :by)""")
                    .param("id", id).param("key", key).param("version", version).param("name", draft.name())
                    .param("graph", json.write(draft.graph())).param("at", Times.toDb(now)).param("by", userId)
                    .update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("WF_PUBLISH_CONFLICT", "Someone else published this process at the same time; try again");
        }
        return byId(id).orElseThrow();
    }

    public void retire(String key) {
        int changed = jdbc.sql("UPDATE process_definition SET status = 'RETIRED' WHERE process_key = :key")
                .param("key", key).update();
        if (changed == 0) {
            throw ApiException.notFound("WF_PROCESS_NOT_FOUND", "No published process '" + key + "'");
        }
    }

    public Optional<StoredDefinition> latestPublished(String key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM process_definition WHERE process_key = :key AND status = 'PUBLISHED'"
                        + " ORDER BY version DESC LIMIT 1")
                .param("key", key).query(this::mapDefinition).optional();
    }

    public Optional<StoredDefinition> version(String key, int version) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM process_definition WHERE process_key = :key AND version = :version")
                .param("key", key).param("version", version).query(this::mapDefinition).optional();
    }

    public Optional<StoredDefinition> byId(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM process_definition WHERE id = :id")
                .param("id", id).query(this::mapDefinition).optional();
    }

    public List<CatalogEntry> catalog() {
        return jdbc.sql("""
                        SELECT d.process_key, d.name, d.version FROM process_definition d
                        WHERE d.status = 'PUBLISHED' AND d.version = (
                            SELECT MAX(x.version) FROM process_definition x
                            WHERE x.process_key = d.process_key AND x.status = 'PUBLISHED')
                        ORDER BY d.name, d.process_key""")
                .query((rs, n) -> new CatalogEntry(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .list();
    }

    public List<DefinitionSummary> list() {
        Map<String, DefinitionSummary> byKey = new TreeMap<>();
        jdbc.sql("""
                        SELECT d.process_key, d.name, d.version, d.status FROM process_definition d
                        WHERE d.version = (SELECT MAX(x.version) FROM process_definition x WHERE x.process_key = d.process_key)""")
                .query(rs -> {
                    byKey.put(rs.getString(1), new DefinitionSummary(rs.getString(1), rs.getString(2), rs.getInt(3),
                            rs.getString(4), false, null));
                });
        jdbc.sql("SELECT process_key, name, updated_at FROM process_draft").query(rs -> {
            String key = rs.getString(1);
            Instant updatedAt = Times.fromDb(rs.getObject(3, LocalDateTime.class));
            DefinitionSummary published = byKey.get(key);
            byKey.put(key, published == null
                    ? new DefinitionSummary(key, rs.getString(2), null, "DRAFT", true, updatedAt)
                    : new DefinitionSummary(key, published.name(), published.latestVersion(), published.status(), true, updatedAt));
        });
        return new ArrayList<>(byKey.values());
    }

    /** Mapping errors first; only a well-formed graph goes through the engine's publish validation. */
    static List<ValidationError> validate(String key, int version, String name, Map<String, Object> graph) {
        DefinitionJson.Result result = DefinitionJson.read(key, version, name, graph);
        return result.ok() ? DefinitionValidator.validate(result.definition()) : result.errors();
    }

    /** The engine's rules and each form setting's shape first; the referenced forms only for an otherwise valid process. */
    private List<ValidationError> check(String key, int version, String name, Map<String, Object> graph) {
        ProcessForms.Result forms = ProcessForms.read(graph);
        List<ValidationError> errors = new ArrayList<>(validate(key, version, name, graph));
        errors.addAll(forms.errors());
        return errors.isEmpty() ? formChecks.check(forms.forms()) : errors;
    }

    private Draft requireDraft(String key) {
        return draft(key).orElseThrow(
                () -> ApiException.notFound("WF_DRAFT_NOT_FOUND", "There is no draft for process '" + key + "'"));
    }

    private StoredDefinition mapDefinition(ResultSet rs, int row) throws SQLException {
        return new StoredDefinition(rs.getString("id"), rs.getString("process_key"), rs.getInt("version"),
                rs.getString("name"), rs.getString("status"), json.readMap(rs.getString("graph_json")),
                Times.fromDb(rs.getObject("published_at", LocalDateTime.class)), rs.getString("published_by"));
    }
}
