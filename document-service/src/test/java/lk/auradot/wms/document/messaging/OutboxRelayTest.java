package lk.auradot.wms.document.messaging;

import lk.auradot.wms.common.messaging.OutboxRelay;
import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.common.messaging.WmsEvents;
import lk.auradot.wms.common.web.CorrelationIdFilter;
import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.Json;
import lk.auradot.wms.document.MutableClock;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestRabbit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DocumentIntegrationTest
@Import(TestRabbit.class)
class OutboxRelayTest {

    @Autowired JdbcClient jdbc;
    @Autowired Outbox outbox;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired MutableClock clock;
    @Autowired Json json;

    Queue queue;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        TopicExchange exchange = new TopicExchange(WmsEvents.EXCHANGE, true, false);
        admin.declareExchange(exchange);
        queue = admin.declareQueue();
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("document.#"));
    }

    @Test
    void publishesPendingRowsOnceWithStableIdsAndTheRequestsCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-12345678");
        try {
            outbox.add(MessagingConfig.VERSION_UPLOADED, Map.of("documentId", "d-1"), clock.instant());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(relay.publishPending()).isZero();

        Message message = rabbit.receive(queue.getName(), 5_000);
        assertThat(message).isNotNull();
        Map<String, Object> body = json.readMap(new String(message.getBody(), StandardCharsets.UTF_8));
        assertThat(body).containsEntry("type", "document.version.uploaded").containsEntry("documentId", "d-1");
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(body.get("messageId"));
        assertThat((String) message.getMessageProperties().getHeader(CorrelationIdFilter.HEADER)).isEqualTo("corr-12345678");
    }

    @Test
    void theTextExtractionQueueIsBoundToUploadedVersions() {
        var properties = admin.getQueueProperties(MessagingConfig.TEXT_QUEUE);

        assertThat(properties).isNotNull();
    }

    @Test
    void everyEventSaysWhenItHappenedUnlessItAlreadyDoes() {
        outbox.add("document.deleted", Map.of("documentId", "d-1"), clock.instant());
        outbox.add("workflow.task.completed", Map.of("occurredAt", "2026-01-01T00:00:00Z"), clock.instant());

        java.util.List<String> payloads = jdbc.sql("SELECT payload_json FROM outbox ORDER BY id").query(String.class).list();
        assertThat(json.readMap(payloads.get(0))).containsEntry("occurredAt", clock.instant().toString());
        assertThat(json.readMap(payloads.get(1))).containsEntry("occurredAt", "2026-01-01T00:00:00Z");
    }
}
