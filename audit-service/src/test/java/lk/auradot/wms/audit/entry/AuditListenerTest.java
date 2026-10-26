package lk.auradot.wms.audit.entry;

import lk.auradot.wms.audit.AuditAdminDb;
import lk.auradot.wms.audit.AuditIntegrationTest;
import lk.auradot.wms.common.web.CorrelationIdFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §8: audit-service consumes all events into audit_entry. */
@AuditIntegrationTest
class AuditListenerTest {

    @Autowired AuditListener listener;
    @Autowired AuditAdminDb admin;
    @Autowired JsonMapper json;

    @BeforeEach
    void reset() {
        admin.clear();
    }

    Message message(String routingKey, byte[] body) {
        return MessageBuilder.withBody(body).setReceivedRoutingKey(routingKey)
                .setHeader(CorrelationIdFilter.HEADER, "corr-7").build();
    }

    Message message(String routingKey, Map<String, Object> event) {
        return message(routingKey, json.writeValueAsBytes(event));
    }

    long entries() {
        return admin.jdbc().sql("SELECT COUNT(*) FROM audit_entry").query(Long.class).single();
    }

    @Test
    void anEventBecomesOneEntryHoweverOftenItIsDelivered() {
        Map<String, Object> event = Map.of("messageId", "m-1", "type", "workflow.task.completed", "instanceId", "i-1",
                "actorId", "bob", "occurredAt", "2026-10-01T09:59:58Z", "data", Map.of("taskId", "t-1", "outcome", "APPROVE"));

        listener.onEvent(message("workflow.task.completed", event));
        listener.onEvent(message("workflow.task.completed", event));

        assertThat(entries()).isEqualTo(1);
        Map<String, Object> row = admin.jdbc().sql("""
                        SELECT action, actor_id, entity_type, entity_id, correlation_id,
                               JSON_UNQUOTE(JSON_EXTRACT(data_json, '$.data.outcome')) AS outcome,
                               DATE_FORMAT(occurred_at, '%Y-%m-%dT%H:%i:%s') AS at
                        FROM audit_entry""").query().singleRow();
        assertThat(row).containsEntry("action", "workflow.task.completed").containsEntry("actor_id", "bob")
                .containsEntry("entity_type", "TASK").containsEntry("entity_id", "t-1")
                .containsEntry("correlation_id", "corr-7").containsEntry("outcome", "APPROVE")
                .containsEntry("at", "2026-10-01T09:59:58");
    }

    @Test
    void unreadableMessagesAreDroppedWithoutStoppingTheQueue() {
        listener.onEvent(message("workflow.task.completed", "not json".getBytes(StandardCharsets.UTF_8)));
        listener.onEvent(message("workflow.task.completed", "[1, 2]".getBytes(StandardCharsets.UTF_8)));
        listener.onEvent(message("workflow.task.completed", "null".getBytes(StandardCharsets.UTF_8)));
        listener.onEvent(message("workflow.task.completed", Map.of("instanceId", "i-1"))); // no messageId

        assertThat(entries()).isZero();
    }

    @Test
    void eventsOfAnUnknownKindAndEventsWithoutAPersonAreRecorded() {
        listener.onEvent(message("identity.user.updated", Map.of("messageId", "m-2", "actorId", "alice")));
        listener.onEvent(message("workflow.task.reminder", Map.of("messageId", "m-3", "instanceId", "i-1",
                "data", Map.of("taskId", "t-1"))));

        assertThat(admin.jdbc().sql("SELECT entity_type FROM audit_entry WHERE message_id = 'm-2'").query(String.class).single())
                .isEqualTo("IDENTITY");
        assertThat(admin.jdbc().sql("SELECT COUNT(*) FROM audit_entry WHERE message_id = 'm-3' AND actor_id IS NULL")
                .query(Long.class).single()).isEqualTo(1);
    }
}
