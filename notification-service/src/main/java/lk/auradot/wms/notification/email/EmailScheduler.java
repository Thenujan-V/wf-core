package lk.auradot.wms.notification.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wms.notification.email.dispatch-enabled", havingValue = "true", matchIfMissing = true)
class EmailScheduler {

    private static final Logger log = LoggerFactory.getLogger(EmailScheduler.class);

    private final EmailDispatcher dispatcher;

    EmailScheduler(EmailDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${wms.notification.email.dispatch-interval:PT5S}")
    void run() {
        try {
            dispatcher.sendPending();
        } catch (RuntimeException e) {
            log.warn("Could not send queued emails; they stay queued: {}", e.getMessage());
        }
    }
}
