package lk.auradot.wms.notification.events;

import lk.auradot.wms.common.messaging.ProcessedMessages;
import lk.auradot.wms.notification.directory.Directory;
import lk.auradot.wms.notification.directory.Person;
import lk.auradot.wms.notification.email.EmailStore;
import lk.auradot.wms.notification.inbox.NotificationStore;
import lk.auradot.wms.notification.inbox.NotificationView;
import lk.auradot.wms.notification.plan.NotificationPlanner;
import lk.auradot.wms.notification.plan.Planned;
import lk.auradot.wms.notification.plan.ProcessEmail;
import lk.auradot.wms.notification.preference.Preference;
import lk.auradot.wms.notification.preference.PreferenceStore;
import lk.auradot.wms.notification.push.LivePush;
import lk.auradot.wms.notification.text.Templates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Spec §8: events become in-app notifications and queued emails, honouring each person's preferences.
 * Everything that asks another service (who the members are, what someone's address is) happens first; then one
 * transaction marks the message processed and stores the result, so a redelivered event changes nothing.
 */
@Component
public class NotificationListener {

    public static final String LISTENER_ID = "notifications";
    public static final String CONSUMER = "notifications";

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
    private static final TypeReference<Map<String, Object>> EVENT = new TypeReference<>() {
    };

    /** What a person's id can look like; free text from a form is not looked up in identity-service. */
    private static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** What will be stored for one recipient, worked out before the transaction. */
    private record Delivery(Planned planned, String title, String body, boolean inApp, String emailTo) {
    }

    /** An email a process asked for, ready to queue. */
    private record Mail(String to, String subject, String body) {
    }

    /** A notification that was stored, to be pushed to its owner once the transaction has committed. */
    private record Pushed(String userId, NotificationView notification) {
    }

    private final JsonMapper mapper;
    private final NotificationPlanner planner;
    private final Directory directory;
    private final PreferenceStore preferences;
    private final Templates templates;
    private final NotificationStore notifications;
    private final EmailStore emails;
    private final LivePush livePush;
    private final ProcessedMessages processed;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final String appUrl;

    public NotificationListener(JsonMapper mapper, NotificationPlanner planner, Directory directory, PreferenceStore preferences,
                                Templates templates, NotificationStore notifications, EmailStore emails, LivePush livePush,
                                ProcessedMessages processed, TransactionTemplate tx, Clock clock,
                                @Value("${wms.notification.app-url}") String appUrl) {
        this.mapper = mapper;
        this.planner = planner;
        this.directory = directory;
        this.preferences = preferences;
        this.templates = templates;
        this.notifications = notifications;
        this.emails = emails;
        this.livePush = livePush;
        this.processed = processed;
        this.tx = tx;
        this.clock = clock;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
    }

    @RabbitListener(id = LISTENER_ID, queues = NotificationMessaging.QUEUE)
    public void onEvent(Message message) {
        String routingKey = message.getMessageProperties().getReceivedRoutingKey();
        Map<String, Object> event;
        try {
            event = mapper.readValue(message.getBody(), EVENT);
        } catch (JacksonException e) {
            event = null;
        }
        String messageId = event != null && event.get("messageId") instanceof String id && !id.isBlank() ? id : null;
        if (messageId == null || messageId.length() > 36) {
            // Not an event; retrying cannot make it one.
            log.warn("Ignoring a {} message that is not an event with a message id", routingKey);
            return;
        }
        if (processed.isProcessed(messageId, CONSUMER)) {
            return;
        }
        // Outside the transaction: these ask identity-service, and throw (to be retried) when it cannot answer.
        List<Delivery> deliveries = deliveries(planner.plan(routingKey, event));
        Optional<Mail> processMail = processMail(routingKey, event);
        Instant now = clock.instant();
        List<Pushed> stored = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                if (!processed.markProcessed(messageId, CONSUMER, now)) {
                    return;
                }
                for (Delivery delivery : deliveries) {
                    store(delivery, now).ifPresent(view -> stored.add(new Pushed(delivery.planned().userId(), view)));
                }
                processMail.ifPresent(mail -> emails.queue(mail.to(), mail.subject(), mail.body(), now));
            });
        } catch (DataIntegrityViolationException e) {
            // The rows themselves are refused; trying again cannot help. Anything else is thrown and retried.
            log.error("A {} event could not be turned into notifications: {}", routingKey, e.getMessage());
            return;
        }
        // Only after the commit, so the app is never told about a notification that does not exist.
        for (Pushed pushed : stored) {
            try {
                livePush.created(pushed.userId(), pushed.notification());
            } catch (RuntimeException e) {
                log.warn("A live push failed; the notification is stored and the app catches up: {}", e.getMessage());
            }
        }
    }

    private List<Delivery> deliveries(List<Planned> plan) {
        List<Delivery> deliveries = new ArrayList<>();
        for (Planned planned : plan) {
            Optional<Person> person = directory.person(planned.userId());
            if (person.isEmpty()) {
                continue; // nobody has this id (any more)
            }
            Preference preference = preferences.of(planned.userId(), planned.kind());
            String email = person.get().email();
            boolean sendEmail = preference.email() && person.get().enabled() && email != null && !email.isBlank();
            if (!preference.inApp() && !sendEmail) {
                continue;
            }
            deliveries.add(new Delivery(planned, templates.render(planned.kind() + ".title", planned.values()),
                    templates.render(planned.kind() + ".body", planned.values()), preference.inApp(), sendEmail ? email : null));
        }
        return deliveries;
    }

    /** Stores what one recipient gets; returns their in-app notification, if they get one. */
    private Optional<NotificationView> store(Delivery delivery, Instant now) {
        Planned planned = delivery.planned();
        NotificationView stored = null;
        if (delivery.inApp()) {
            stored = notifications.insert(planned.userId(), planned.kind(), delivery.title(), delivery.body(), planned.link(), now);
        }
        if (delivery.emailTo() != null) {
            Map<String, String> link = new LinkedHashMap<>();
            link.put("url", appUrl + planned.link());
            emails.queue(delivery.emailTo(), delivery.title(),
                    delivery.body() + "\n\n" + templates.render("email.link", link), now);
        }
        return Optional.ofNullable(stored);
    }

    /**
     * The email a process's "send email" step asked for. It is not a matter of preference: the process wants it sent.
     * An address that cannot be worked out is skipped with a warning; it would not help to try again.
     */
    private Optional<Mail> processMail(String routingKey, Map<String, Object> event) {
        if (!NotificationPlanner.EMAIL_REQUESTED.equals(routingKey)) {
            return Optional.empty();
        }
        Optional<ProcessEmail> requested = planner.processEmail(routingKey, event);
        if (requested.isEmpty()) {
            log.warn("A process asked for an email, but where to send it could not be worked out (request {})", event.get("instanceId"));
            return Optional.empty();
        }
        ProcessEmail email = requested.get();
        String to = email.to();
        if (!to.contains("@")) {
            String userId = to;
            if (!USER_ID.matcher(userId).matches()) {
                log.warn("A process asked for an email, but its recipient is neither an address nor a person (request {})", event.get("instanceId"));
                return Optional.empty();
            }
            to = directory.person(userId).filter(Person::enabled).map(Person::email)
                    .filter(address -> address != null && !address.isBlank()).orElse(null);
            if (to == null) {
                log.warn("A process asked for an email to {}, who has no address (request {})", userId, event.get("instanceId"));
                return Optional.empty();
            }
        }
        String key = "process." + email.template();
        if (!templates.has(key + ".subject") || !templates.has(key + ".body")) {
            key = "process.default";
        }
        return Optional.of(new Mail(to, templates.render(key + ".subject", email.values()), templates.render(key + ".body", email.values())));
    }
}
