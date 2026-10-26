package lk.auradot.wms.document.staging;

import lk.auradot.wms.document.Json;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.common.messaging.ProcessedMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** workflow.files.submitted: a submitted form's files become the request's attachments (spec §5, §6). */
@Component
public class RequestFilesListener {

    public static final String LISTENER_ID = "request-files";
    public static final String CONSUMER = "request-files";

    private static final Logger log = LoggerFactory.getLogger(RequestFilesListener.class);

    private final Json json;
    private final StagedFiles staged;
    private final ProcessedMessages processed;
    private final TransactionTemplate tx;
    private final Clock clock;

    public RequestFilesListener(Json json, StagedFiles staged, ProcessedMessages processed, TransactionTemplate tx, Clock clock) {
        this.json = json;
        this.staged = staged;
        this.processed = processed;
        this.tx = tx;
        this.clock = clock;
    }

    @RabbitListener(id = LISTENER_ID, queues = MessagingConfig.REQUEST_FILES_QUEUE)
    public void onFilesSubmitted(Message message) {
        Map<String, Object> event = json.readMap(new String(message.getBody(), StandardCharsets.UTF_8));
        String messageId = text(event, "messageId");
        String instanceId = text(event, "instanceId");
        String processKey = text(event, "processKey");
        String actorId = text(event, "actorId");
        if (messageId == null || instanceId == null || processKey == null || actorId == null
                || !(event.get("documentIds") instanceof List<?> ids)) {
            log.warn("Ignoring a malformed {} message", MessagingConfig.FILES_SUBMITTED);
            return;
        }
        List<String> documentIds = ids.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            if (processed.markProcessed(messageId, CONSUMER, now)) {
                staged.attach(instanceId, processKey, actorId, documentIds, now);
            }
        });
    }

    private static String text(Map<String, Object> event, String key) {
        return event.get(key) instanceof String value && !value.isBlank() ? value : null;
    }
}
