package lk.auradot.wms.audit.entry;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a domain event into an audit entry. The action is the routing key ({@code <service>.<entity>.<event>});
 * the entity is worked out from it, so events of a kind nobody planned for are still recorded.
 */
public final class AuditEvents {

    static final int MESSAGE_ID_MAX = 36;
    static final int ACTION_MAX = 100;
    static final int ACTOR_MAX = 255;
    static final int ENTITY_TYPE_MAX = 40;
    static final int ENTITY_ID_MAX = 64;
    static final int CORRELATION_MAX = 64;

    private record Entity(String type, String id) {
    }

    private AuditEvents() {
    }

    /** Empty when the event cannot be recorded: no message id to recognise it by, or no action. */
    public static Optional<NewAuditEntry> entry(String routingKey, Map<String, Object> event, String correlationId,
                                                Instant receivedAt) {
        String messageId = text(event.get("messageId"));
        String action = routingKey != null && !routingKey.isBlank() ? routingKey : text(event.get("type"));
        if (messageId == null || messageId.length() > MESSAGE_ID_MAX || action == null) {
            return Optional.empty();
        }
        Entity entity = entity(action, event);
        return Optional.of(new NewAuditEntry(messageId, cut(action, ACTION_MAX), cut(text(event.get("actorId")), ACTOR_MAX),
                cut(entity.type(), ENTITY_TYPE_MAX), cut(entity.id(), ENTITY_ID_MAX), event,
                cut(correlationId, CORRELATION_MAX), occurredAt(event, receivedAt)));
    }

    private static Entity entity(String action, Map<String, Object> event) {
        String[] parts = action.split("\\.");
        Map<?, ?> data = event.get("data") instanceof Map<?, ?> map ? map : Map.of();
        if ("workflow".equals(parts[0])) {
            return switch (parts.length > 1 ? parts[1] : "") {
                case "task" -> new Entity("TASK", text(data.get("taskId")));
                case "job" -> new Entity("JOB", text(data.get("jobId")));
                default -> new Entity("INSTANCE", text(event.get("instanceId")));
            };
        }
        if ("document".equals(parts[0])) {
            return new Entity("DOCUMENT", text(event.get("documentId")));
        }
        return new Entity(parts[0].toUpperCase(Locale.ROOT), null);
    }

    /** The event's own time when it carries one; otherwise the time it arrived. */
    private static Instant occurredAt(Map<String, Object> event, Instant receivedAt) {
        String text = text(event.get("occurredAt"));
        if (text == null) {
            return receivedAt;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            return receivedAt;
        }
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** Over-long values are cut, not refused: a refused event would be retried and then lost. */
    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
