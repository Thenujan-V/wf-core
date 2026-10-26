package lk.auradot.wms.common.messaging;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;

/**
 * Idempotent consumers (spec §2.2): call markProcessed in the same transaction as the work it guards. The service
 * owns the {@code processed_message} table (message_id, consumer, processed_at).
 */
public class ProcessedMessages {

    private final JdbcClient jdbc;

    public ProcessedMessages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when this consumer already handled the message. */
    public boolean markProcessed(String messageId, String consumer, Instant now) {
        try {
            jdbc.sql("INSERT INTO processed_message (message_id, consumer, processed_at) VALUES (:id, :consumer, :at)")
                    .param("id", messageId).param("consumer", consumer).param("at", Outbox.utc(now)).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public boolean isProcessed(String messageId, String consumer) {
        return jdbc.sql("SELECT COUNT(*) FROM processed_message WHERE message_id = :id AND consumer = :consumer")
                .param("id", messageId).param("consumer", consumer).query(Long.class).single() > 0;
    }
}
