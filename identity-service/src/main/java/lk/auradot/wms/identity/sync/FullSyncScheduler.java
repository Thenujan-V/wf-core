package lk.auradot.wms.identity.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wms.identity.sync.enabled", havingValue = "true", matchIfMissing = true)
class FullSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(FullSyncScheduler.class);

    private final FullSyncService sync;

    FullSyncScheduler(FullSyncService sync) {
        this.sync = sync;
    }

    @Scheduled(fixedDelayString = "${wms.identity.sync.interval:PT15M}",
            initialDelayString = "${wms.identity.sync.initial-delay:PT30S}")
    void run() {
        try {
            sync.runOnce();
        } catch (RuntimeException e) {
            log.error("Directory sync failed; will retry at the next interval", e);
        }
    }
}
