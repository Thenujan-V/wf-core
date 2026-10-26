package lk.auradot.wms.notification.push;

import lk.auradot.wms.notification.MutableClock;
import lk.auradot.wms.notification.TestDb;
import lk.auradot.wms.notification.TestNotificationConfiguration;
import lk.auradot.wms.notification.TestRabbit;
import lk.auradot.wms.notification.TestcontainersConfiguration;
import lk.auradot.wms.notification.inbox.NotificationStore;
import lk.auradot.wms.notification.inbox.NotificationView;
import lk.auradot.wms.notification.plan.Kind;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Against a real broker: what one instance stores reaches every instance listening on the push exchange. */
@SpringBootTest(properties = {
        "wms.notification.email.dispatch-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
@Import({TestcontainersConfiguration.class, TestNotificationConfiguration.class, TestRabbit.class})
class BrokerLivePushTest {

    @Autowired BrokerLivePush livePush;
    @Autowired NotificationStore store;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @SuppressWarnings("unchecked")
    Map<String, Object> next(Queue queue) {
        Message message = rabbit.receive(queue.getName(), 5000);
        assertThat(message).as("a message on the push exchange").isNotNull();
        return json.readValue(message.getBody(), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishesWhatWasStoredWithThePersonsUnreadCount() {
        TestDb.clear(jdbc);
        // Not auto-delete: reading with a timeout attaches and detaches a consumer, which would delete such a queue.
        Queue anotherInstance = new Queue("push-probe-" + java.util.UUID.randomUUID(), true, false, false);
        admin.declareQueue(anotherInstance);
        admin.declareBinding(BindingBuilder.bind(anotherInstance).to(new FanoutExchange(PushMessaging.EXCHANGE)));
        NotificationView stored = store.insert("bob", Kind.TASK_ASSIGNED, "New task: Review", "You have a new task.", "/inbox", clock.instant());

        livePush.created("bob", stored);
        livePush.unreadChanged("carol");

        Map<String, Object> created = next(anotherInstance);
        assertThat(created).containsEntry("userId", "bob").containsEntry("type", "created").containsEntry("unread", 1);
        assertThat((Map<String, Object>) created.get("notification")).containsEntry("id", stored.id())
                .containsEntry("title", "New task: Review").containsEntry("link", "/inbox").containsEntry("createdAt", "2026-10-01T09:00:00Z");
        Map<String, Object> unread = next(anotherInstance);
        assertThat(unread).containsEntry("userId", "carol").containsEntry("type", "unread").containsEntry("unread", 0);
        assertThat(unread.get("notification")).isNull();
        admin.deleteQueue(anotherInstance.getName());
    }
}
