package lk.auradot.wms.common.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** Relays the outbox on a timer; the service must have scheduling enabled ({@code @EnableScheduling}). */
public class OutboxScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxScheduler.class);

    private final OutboxRelay relay;

    public OutboxScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${wms.outbox.poll-interval:PT1S}")
    void publish() {
        try {
            relay.publishPending();
        } catch (RuntimeException e) {
            log.warn("Could not publish outbox events; they stay queued and are retried: {}", e.getMessage());
        }
    }
}
