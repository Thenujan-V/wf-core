package lk.auradot.wms.document.text;

import lk.auradot.wms.document.Json;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.common.messaging.ProcessedMessages;
import lk.auradot.wms.document.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Spec §6: a new version's text is extracted asynchronously. The object is copied to a temporary file first, so a
 * storage failure (worth retrying) is told apart from a file without readable text (not worth retrying).
 */
@Component
public class TextExtractionListener {

    public static final String LISTENER_ID = "text-extraction";
    public static final String CONSUMER = "text-extraction";

    private static final Logger log = LoggerFactory.getLogger(TextExtractionListener.class);

    private final Json json;
    private final ObjectStore objects;
    private final TextExtractor extractor;
    private final DocumentTextStore texts;
    private final ProcessedMessages processed;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TextExtractionListener(Json json, ObjectStore objects, TextExtractor extractor, DocumentTextStore texts,
                                  ProcessedMessages processed, TransactionTemplate tx, Clock clock) {
        this.json = json;
        this.objects = objects;
        this.extractor = extractor;
        this.texts = texts;
        this.processed = processed;
        this.tx = tx;
        this.clock = clock;
    }

    @RabbitListener(id = LISTENER_ID, queues = MessagingConfig.TEXT_QUEUE)
    public void onVersionUploaded(Message message) throws IOException {
        Map<String, Object> event = json.readMap(new String(message.getBody(), StandardCharsets.UTF_8));
        String messageId = text(event, "messageId");
        String versionId = text(event, "versionId");
        String documentId = text(event, "documentId");
        String key = text(event, "s3Key");
        if (messageId == null || versionId == null || documentId == null || key == null) {
            log.warn("Ignoring a malformed {} message", MessagingConfig.VERSION_UPLOADED);
            return;
        }
        if (processed.isProcessed(messageId, CONSUMER) || texts.exists(versionId)) {
            markProcessed(messageId);
            return;
        }
        Path file = Files.createTempFile("wms-text-", ".bin");
        try {
            try (InputStream in = objects.open(key)) {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (ObjectStore.MissingObjectException e) {
                log.info("Version {} has no stored file any more; nothing to extract", versionId);
                markProcessed(messageId);
                return;
            }
            String text = extractor.extract(file);
            Instant now = clock.instant();
            tx.executeWithoutResult(status -> {
                if (processed.markProcessed(messageId, CONSUMER, now)) {
                    texts.saveIfVersionExists(versionId, documentId, text, now);
                }
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private void markProcessed(String messageId) {
        tx.executeWithoutResult(status -> processed.markProcessed(messageId, CONSUMER, clock.instant()));
    }

    private static String text(Map<String, Object> event, String key) {
        return event.get(key) instanceof String value ? value : null;
    }
}
