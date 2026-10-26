package lk.auradot.wms.document.written;

import lk.auradot.wms.common.messaging.ProcessedMessages;
import lk.auradot.wms.document.Json;
import lk.auradot.wms.document.messaging.MessagingConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** workflow.instance.completed / cancelled: a request a written document was sent to is over (written-documents spec §8). */
@Component
public class ApprovalOutcomeListener {

    public static final String LISTENER_ID = "approvals";
    public static final String CONSUMER = "approvals";

    private static final Logger log = LoggerFactory.getLogger(ApprovalOutcomeListener.class);

    private final Json json;
    private final SubmissionService submissions;
    private final ProcessedMessages processed;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApprovalSettlement settlement;

    public ApprovalOutcomeListener(Json json, SubmissionService submissions, ProcessedMessages processed,
                                   TransactionTemplate tx, Clock clock, ApprovalSettlement settlement) {
        this.settlement = settlement;
        this.json = json;
        this.submissions = submissions;
        this.processed = processed;
        this.tx = tx;
        this.clock = clock;
    }

    @RabbitListener(id = LISTENER_ID, queues = MessagingConfig.APPROVALS_QUEUE)
    public void onRequestEnded(Message message) {
        Map<String, Object> event = json.readMap(new String(message.getBody(), StandardCharsets.UTF_8));
        String messageId = text(event, "messageId");
        String instanceId = text(event, "instanceId");
        if (messageId == null || instanceId == null) {
            log.warn("Ignoring a malformed request-ended message");
            return;
        }
        // The end status is inside "data" (the engine's event); a cancelled request has none.
        String endStatus = event.get("data") instanceof Map<?, ?> data && data.get("endStatus") instanceof String status
                && !status.isBlank() ? status : null;
        Instant now = clock.instant();
        String[] toSettle = new String[1];
        tx.executeWithoutResult(status -> {
            if (processed.markProcessed(messageId, CONSUMER, now)) {
                toSettle[0] = submissions.ended(instanceId, endStatus, now).orElse(null);
            }
        });
        // Outside the transaction, and never failing the message: what cannot be settled now is settled by the schedule.
        if (toSettle[0] != null) {
            settlement.trySettle(toSettle[0]);
        }
    }

    private static String text(Map<String, Object> event, String key) {
        return event.get(key) instanceof String value && !value.isBlank() ? value : null;
    }
}
