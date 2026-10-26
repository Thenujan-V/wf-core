package lk.auradot.wms.workflow.outbox;

import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.common.messaging.OutboxProperties;
import lk.auradot.wms.common.messaging.OutboxRelay;
import lk.auradot.wms.common.messaging.WmsEvents;
import lk.auradot.wms.common.web.CorrelationIdFilter;
import lk.auradot.wms.workflow.Json;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WorkflowIntegrationTest
@Import(OutboxRelayTest.Rabbit.class)
class OutboxRelayTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Rabbit {
        static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitContainer() {
            return RABBIT;
        }
    }

    @Autowired JdbcClient jdbc;
    @Autowired Outbox outbox;
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin admin;
    @Autowired TransactionTemplate tx;
    @Autowired MutableClock clock;
    @Autowired Json json;

    Queue queue;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        TopicExchange exchange = new TopicExchange(WmsEvents.EXCHANGE, true, false);
        admin.declareExchange(exchange);
        queue = admin.declareQueue();
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("workflow.#"));
    }

    @Test
    void publishesPendingRowsOnceWithStableIds() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-12345678");
        try {
            outbox.add("workflow.task.created", Map.of("instanceId", "i-1"), clock.instant());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(relay.publishPending()).isZero();

        Message message = rabbit.receive(queue.getName(), 5_000);
        assertThat(message).isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo("workflow.task.created");
        assertThat(message.getMessageProperties().getMessageId()).isNotBlank();
        assertThat((String) message.getMessageProperties().getHeader("X-Correlation-Id")).isEqualTo("corr-12345678");
        assertThat(json.readMap(new String(message.getBody(), StandardCharsets.UTF_8)))
                .containsEntry("instanceId", "i-1")
                .containsEntry("messageId", message.getMessageProperties().getMessageId());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Integer.class).single()).isZero();
    }

    @Test
    void anUnavailableBrokerLeavesRowsForLater() throws Exception {
        outbox.add("workflow.task.created", Map.of("instanceId", "i-2"), clock.instant());
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        CachingConnectionFactory down = new CachingConnectionFactory("127.0.0.1", closedPort);
        down.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        var brokenRelay = new OutboxRelay(outbox, new RabbitTemplate(down), tx, clock,
                new OutboxProperties(true, java.time.Duration.ofSeconds(1), 100));

        assertThatThrownBy(brokenRelay::publishPending).isInstanceOf(RuntimeException.class);

        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL").query(Integer.class).single()).isEqualTo(1);
        assertThat(relay.publishPending()).as("published once the broker is reachable").isEqualTo(1);
        down.destroy();
    }

    @Test
    void aSlowBrokerDoesNotBlockNewEventsFromBeingWritten() throws Exception {
        outbox.add("workflow.task.created", Map.of("instanceId", "i-3"), clock.instant());
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RabbitTemplate slowBroker = new RabbitTemplate(rabbit.getConnectionFactory()) {
            @Override
            public <T> T invoke(OperationsCallback<T> action) {
                sending.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.invoke(action);
            }
        };
        var slowRelay = new OutboxRelay(outbox, slowBroker, tx, clock, new OutboxProperties(true, Duration.ofSeconds(1), 100));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> relaying = pool.submit(slowRelay::publishPending);
            assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> writing = pool.submit(() -> tx.execute(status -> {
                outbox.add("workflow.task.created", Map.of("instanceId", "i-4"), clock.instant());
                return null;
            }));
            try {
                writing.get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertThat(relaying.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
