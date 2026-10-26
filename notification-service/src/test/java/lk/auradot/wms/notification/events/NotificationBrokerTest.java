package lk.auradot.wms.notification.events;

import lk.auradot.wms.common.messaging.WmsEvents;
import lk.auradot.wms.notification.FakeDirectory;
import lk.auradot.wms.notification.TestDb;
import lk.auradot.wms.notification.TestNotificationConfiguration;
import lk.auradot.wms.notification.TestRabbit;
import lk.auradot.wms.notification.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real broker: the queue is bound to the events the planner knows, and an identity outage loses nothing. */
@SpringBootTest(properties = {
        "wms.notification.email.dispatch-enabled=false",
        "spring.rabbitmq.listener.simple.retry.initial-interval=100ms",
        "spring.rabbitmq.listener.simple.retry.max-retries=1"})
@Import({TestcontainersConfiguration.class, TestNotificationConfiguration.class, TestRabbit.class})
class NotificationBrokerTest {

    @Autowired RabbitTemplate rabbit;
    @Autowired FakeDirectory directory;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        directory.person("bob", "Bob Manager", "bob@example.com");
    }

    void publish(String messageId) {
        Map<String, Object> event = Map.of("messageId", messageId, "instanceId", "i-1", "processKey", "expense-request",
                "initiatorId", "carol", "actorId", "carol", "taskName", "Manager approval",
                "data", Map.of("taskId", "t-1", "assigneeId", "bob", "candidates", List.of()));
        rabbit.send(WmsEvents.EXCHANGE, "workflow.task.created", MessageBuilder.withBody(json.writeValueAsBytes(event)).build());
    }

    boolean toldWithin(int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (jdbc.sql("SELECT COUNT(*) FROM notification WHERE user_id = 'bob'").query(Long.class).single() == 1) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    void anEventPublishedOnTheExchangeBecomesANotification() throws Exception {
        publish("m-1");

        assertThat(toldWithin(15)).isTrue();
    }

    @Test
    void anEventThatArrivesWhileTheDirectoryIsDownIsHandledOnceItIsBack() throws Exception {
        directory.down(true);
        publish("m-2");
        Thread.sleep(1500); // longer than one round of retries
        directory.down(false);

        assertThat(toldWithin(20)).isTrue();
    }
}
