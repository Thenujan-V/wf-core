package lk.auradot.wms.common.messaging;

public record OutboxRow(long id, String messageId, String routingKey, String payloadJson, String correlationId) {
}
