package lk.auradot.wms.audit.entry;

import lk.auradot.wms.audit.AuditAdminDb;
import lk.auradot.wms.audit.TestRabbit;
import lk.auradot.wms.audit.TestcontainersConfiguration;
import lk.auradot.wms.common.messaging.WmsEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real broker: an audit trail may be late, but it must not lose events. */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-retries=1"})
@Import({TestcontainersConfiguration.class, TestRabbit.class, AuditBrokerTest.FlakyDatabase.class})
class AuditBrokerTest {

    /** The real store, with a database that can be made to fail. */
    static class FlakyStore extends AuditStore {

        private final AtomicInteger outages = new AtomicInteger();
        private volatile boolean refusing;

        /** Spring proxies the store, so its state is set through methods. */
        public void failNext(int inserts) {
            outages.set(inserts);
        }

        public void refuse(boolean refuse) {
            refusing = refuse;
        }

        FlakyStore(JdbcClient jdbc, JsonMapper mapper) {
            super(jdbc, mapper);
        }

        @Override
        public boolean insert(NewAuditEntry entry, Instant now) {
            if (refusing) {
                throw new DataIntegrityViolationException("value too long (test)");
            }
            if (outages.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
                throw new DataAccessResourceFailureException("the database is down (test)");
            }
            return super.insert(entry, now);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FlakyDatabase {

        @Bean
        @Primary
        FlakyStore flakyStore(JdbcClient jdbc, JsonMapper mapper) {
            return new FlakyStore(jdbc, mapper);
        }
    }

    @Autowired RabbitTemplate rabbit;
    @Autowired AuditAdminDb admin;
    @Autowired FlakyStore store;
    @Autowired JsonMapper json;

    @BeforeEach
    void reset() {
        admin.clear();
        store.failNext(0);
        store.refuse(false);
    }

    void publish(String routingKey, String messageId) {
        byte[] body = json.writeValueAsBytes(Map.of("messageId", messageId, "instanceId", "i-1", "actorId", "carol"));
        rabbit.send(WmsEvents.EXCHANGE, routingKey, MessageBuilder.withBody(body).build());
    }

    long recorded(String messageId) {
        return admin.jdbc().sql("SELECT COUNT(*) FROM audit_entry WHERE message_id = :id").param("id", messageId)
                .query(Long.class).single();
    }

    static boolean within(int seconds, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    @Test
    void anEventPublishedOnTheExchangeIsRecorded() throws Exception {
        publish("workflow.instance.started", "m-1");

        assertThat(within(15, () -> recorded("m-1") == 1)).isTrue();
    }

    @Test
    void eventsThatArriveDuringADatabaseOutageAreRecordedOnceItIsBack() throws Exception {
        store.failNext(5); // more failures than one round of retries

        publish("workflow.task.completed", "m-2");

        assertThat(within(20, () -> recorded("m-2") == 1)).isTrue();
    }

    @Test
    void anEventThatCanNeverBeStoredIsSetAsideRatherThanLostOrRetriedForEver() throws Exception {
        store.refuse(true);

        publish("workflow.instance.started", "m-3");

        Message kept = rabbit.receive(AuditMessaging.FAILED_QUEUE, 15_000);
        assertThat(kept).isNotNull();
        assertThat(new String(kept.getBody(), StandardCharsets.UTF_8)).contains("m-3");
        assertThat(recorded("m-3")).isZero();
    }
}
