package lk.auradot.wms.audit.entry;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** audit_entry is append-only: this class inserts and reads, and the database user can do nothing else. */
@Repository
public class AuditStore {

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public AuditStore(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** False when this message was recorded before (the outbox relay delivers at least once). */
    public boolean insert(NewAuditEntry entry, Instant now) {
        try {
            jdbc.sql("""
                            INSERT INTO audit_entry (message_id, action, actor_id, entity_type, entity_id, data_json,
                                                     correlation_id, occurred_at, recorded_at)
                            VALUES (:messageId, :action, :actor, :entityType, :entityId, :data, :correlationId, :at, :now)""")
                    .param("messageId", entry.messageId()).param("action", entry.action()).param("actor", entry.actorId())
                    .param("entityType", entry.entityType()).param("entityId", entry.entityId())
                    .param("data", mapper.writeValueAsString(entry.data())).param("correlationId", entry.correlationId())
                    .param("at", utc(entry.occurredAt())).param("now", utc(now))
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** All DATETIME columns hold UTC. */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final TypeReference<Map<String, Object>> DATA = new TypeReference<>() {
    };

    /** Newest first. */
    public AuditPage search(AuditFilter filter) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        equal(where, params, "actor_id", "actor", filter.actor());
        equal(where, params, "entity_type", "entityType", filter.entityType());
        equal(where, params, "entity_id", "entityId", filter.entityId());
        equal(where, params, "action", "action", filter.action());
        if (filter.from() != null) {
            where.add("occurred_at >= :from");
            params.put("from", utc(filter.from()));
        }
        if (filter.to() != null) {
            where.add("occurred_at < :to");
            params.put("to", utc(filter.to()));
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        long total = jdbc.sql("SELECT COUNT(*) FROM audit_entry" + clause).params(params).query(Long.class).single();
        List<AuditEntry> items = jdbc.sql("""
                        SELECT id, action, actor_id, entity_type, entity_id, data_json, correlation_id, occurred_at
                        FROM audit_entry""" + clause + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .params(params).param("limit", filter.size()).param("offset", (long) filter.page() * filter.size())
                .query(this::map).list();
        return new AuditPage(items, total, filter.page(), filter.size());
    }

    private static void equal(List<String> where, Map<String, Object> params, String column, String name, String value) {
        if (value != null) {
            where.add(column + " = :" + name);
            params.put(name, value);
        }
    }

    private AuditEntry map(ResultSet rs, int row) throws SQLException {
        return new AuditEntry(rs.getLong("id"), rs.getString("action"), rs.getString("actor_id"), rs.getString("entity_type"),
                rs.getString("entity_id"), mapper.readValue(rs.getString("data_json"), DATA), rs.getString("correlation_id"),
                rs.getObject("occurred_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
}
