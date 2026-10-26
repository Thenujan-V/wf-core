package lk.auradot.wms.common.messaging;

import lk.auradot.wms.common.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox (spec §2.2): rows are written in the business transaction and relayed afterwards. The service
 * owns the {@code outbox} table (message_id, routing_key, payload_json, correlation_id, created_at, published_at).
 */
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper mapper;

    public Outbox(JdbcClient jdbc, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * Stores the event with the current request's correlation id. The payload gains a stable "messageId", which
     * consumers de-duplicate on, its "type" (the routing key) and, unless it has one, "occurredAt".
     */
    public void add(String routingKey, Map<String, Object> payload, Instant now) {
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", messageId);
        body.put("type", routingKey);
        body.putAll(payload);
        // When it happened, for consumers that record or order events (audit): not when they got round to reading it.
        body.putIfAbsent("occurredAt", now.toString());
        jdbc.sql("""
                        INSERT INTO outbox (message_id, routing_key, payload_json, correlation_id, created_at)
                        VALUES (:messageId, :key, :payload, :correlationId, :at)""")
                .param("messageId", messageId).param("key", routingKey).param("payload", mapper.writeValueAsString(body))
                .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY)).param("at", utc(now))
                .update();
    }

    /** Oldest unpublished rows, locked for the current transaction; other relays skip them. */
    public List<OutboxRow> lockUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, message_id, routing_key, payload_json, correlation_id FROM outbox
                        WHERE published_at IS NULL ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED""")
                .param("limit", limit)
                .query((rs, n) -> new OutboxRow(rs.getLong("id"), rs.getString("message_id"), rs.getString("routing_key"),
                        rs.getString("payload_json"), rs.getString("correlation_id")))
                .list();
    }

    public void markPublished(long id, Instant now) {
        jdbc.sql("UPDATE outbox SET published_at = :at WHERE id = :id").param("at", utc(now)).param("id", id).update();
    }

    /** All DATETIME columns hold UTC. */
    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
