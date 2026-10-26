package lk.auradot.wms.common.messaging;

import lk.auradot.wms.common.web.CorrelationIdFilter;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * Publishes outbox rows at least once: a row is marked published only after the broker confirmed it, in the
 * same transaction that locked it. If anything fails the transaction rolls back and the rows are sent again later
 * with the same messageId, which consumers de-duplicate on.
 */
public class OutboxRelay {

    private final Outbox outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxProperties props;

    public OutboxRelay(Outbox outbox, RabbitTemplate rabbit, TransactionTemplate tx, Clock clock, OutboxProperties props) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        // READ COMMITTED: no gap locks, so new outbox rows can be written while a batch is being sent.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
        this.props = props;
    }

    public int publishPending() {
        Integer published = tx.execute(status -> {
            List<OutboxRow> rows = outbox.lockUnpublished(props.batchSize());
            for (OutboxRow row : rows) {
                send(row);
                outbox.markPublished(row.id(), clock.instant());
            }
            return rows.size();
        });
        return published == null ? 0 : published;
    }

    private void send(OutboxRow row) {
        var builder = MessageBuilder.withBody(row.payloadJson().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(row.messageId());
        if (row.correlationId() != null) {
            builder.setHeader(CorrelationIdFilter.HEADER, row.correlationId());
        }
        Message message = builder.build();
        rabbit.invoke(operations -> {
            operations.send(WmsEvents.EXCHANGE, row.routingKey(), message);
            operations.waitForConfirmsOrDie(5_000);
            return null;
        });
    }
}
