package lk.auradot.wms.audit.entry;

import lk.auradot.wms.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Spec §8: every event on wms.events becomes one audit entry. Nothing readable is thrown away:
 * <ul>
 *   <li>a failure that may pass (the database is down) is thrown, so the message is retried until it is stored;</li>
 *   <li>an event that can never be stored is moved to {@link AuditMessaging#FAILED_QUEUE} and kept there;</li>
 *   <li>only a message that is not an event at all (not a JSON object, no message id) is dropped.</li>
 * </ul>
 */
@Component
public class AuditListener {

    public static final String LISTENER_ID = "audit";

    private static final Logger log = LoggerFactory.getLogger(AuditListener.class);
    private static final TypeReference<Map<String, Object>> EVENT = new TypeReference<>() {
    };

    private final JsonMapper mapper;
    private final AuditStore store;
    private final RabbitTemplate rabbit;
    private final Clock clock;

    public AuditListener(JsonMapper mapper, AuditStore store, RabbitTemplate rabbit, Clock clock) {
        this.mapper = mapper;
        this.store = store;
        this.rabbit = rabbit;
        this.clock = clock;
    }

    @RabbitListener(id = LISTENER_ID, queues = AuditMessaging.QUEUE)
    public void onEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        Map<String, Object> event;
        try {
            event = mapper.readValue(message.getBody(), EVENT);
        } catch (JacksonException e) {
            event = null;
        }
        if (event == null) {
            // Retrying cannot make it readable; dropping it keeps the queue moving.
            log.warn("Ignoring a {} event that is not a JSON object ({} bytes)", routingKey, message.getBody().length);
            return;
        }
        Object header = message.getMessageProperties().getHeader(CorrelationIdFilter.HEADER);
        Instant now = clock.instant();
        Optional<NewAuditEntry> entry;
        try {
            entry = AuditEvents.entry(routingKey, event, header instanceof String id ? id : null, now);
        } catch (RuntimeException e) {
            setAside(message, routingKey, e);
            return;
        }
        if (entry.isEmpty()) {
            log.warn("Ignoring a {} event without a usable message id", routingKey);
            return;
        }
        try {
            store.insert(entry.get(), now);
        } catch (DataIntegrityViolationException e) {
            // The row itself is refused; trying again cannot help. Any other failure is thrown and retried.
            setAside(message, routingKey, e);
        }
    }

    private void setAside(Message message, String routingKey, Exception cause) {
        log.error("A {} event could not be recorded and is kept in {}: {}", routingKey, AuditMessaging.FAILED_QUEUE,
                cause.getMessage());
        rabbit.send("", AuditMessaging.FAILED_QUEUE, message);
    }
}
