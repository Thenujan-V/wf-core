package lk.auradot.wms.notification.push;

import lk.auradot.wms.notification.inbox.NotificationStore;
import lk.auradot.wms.notification.inbox.NotificationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Publishes to the push exchange; every instance forwards to the sessions it holds. Never throws. */
@Component
public class BrokerLivePush implements LivePush {

    private static final Logger log = LoggerFactory.getLogger(BrokerLivePush.class);

    private final RabbitTemplate rabbit;
    private final NotificationStore notifications;
    private final JsonMapper mapper;

    public BrokerLivePush(RabbitTemplate rabbit, NotificationStore notifications, JsonMapper mapper) {
        this.rabbit = rabbit;
        this.notifications = notifications;
        this.mapper = mapper;
    }

    @Override
    public void created(String userId, NotificationView notification) {
        publish(userId, PushMessage.CREATED, notification);
    }

    @Override
    public void unreadChanged(String userId) {
        publish(userId, PushMessage.UNREAD, null);
    }

    private void publish(String userId, String type, NotificationView notification) {
        try {
            PushMessage message = new PushMessage(userId, type, notification, notifications.unreadCount(userId));
            rabbit.send(PushMessaging.EXCHANGE, "", MessageBuilder.withBody(mapper.writeValueAsBytes(message))
                    .setContentType("application/json").build());
        } catch (RuntimeException e) {
            log.warn("A live push could not be published; the app catches up when it next asks: {}", e.getMessage());
        }
    }
}
