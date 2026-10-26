package lk.auradot.wms.notification.push;

import lk.auradot.wms.notification.inbox.NotificationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Hands what any instance published to the sessions this instance holds for that person (if any). */
@Component
public class PushRelay {

    /** What the app receives: the body of a STOMP frame, as JSON. */
    record Frame(String type, NotificationView notification, long unread) {
    }

    private static final Logger log = LoggerFactory.getLogger(PushRelay.class);

    private final SimpMessagingTemplate messaging;
    private final JsonMapper mapper;

    public PushRelay(SimpMessagingTemplate messaging, JsonMapper mapper) {
        this.messaging = messaging;
        this.mapper = mapper;
    }

    @RabbitListener(id = "push", queues = "#{pushQueue.name}")
    public void onPush(Message message) {
        // Never thrown: a push is not worth retrying, and must not go back on the queue.
        try {
            PushMessage push = mapper.readValue(message.getBody(), PushMessage.class);
            if (push == null || push.userId() == null || push.type() == null) {
                return;
            }
            String frame = mapper.writeValueAsString(new Frame(push.type(), push.notification(), push.unread()));
            messaging.convertAndSendToUser(push.userId(), PushSocketConfig.QUEUE, frame);
        } catch (RuntimeException e) {
            log.warn("A live push could not be delivered; the app catches up when it next asks: {}", e.getMessage());
        }
    }
}
