package lk.auradot.wms.document.staging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wms.document.staging.purge-enabled", havingValue = "true", matchIfMissing = true)
class StagedFilePurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(StagedFilePurgeScheduler.class);

    private final StagedFilePurge purge;

    StagedFilePurgeScheduler(StagedFilePurge purge) {
        this.purge = purge;
    }

    @Scheduled(fixedDelayString = "${wms.document.staging.purge-interval:PT1H}")
    void run() {
        try {
            int removed = purge.purge();
            if (removed > 0) {
                log.info("Removed {} staged upload(s) nobody submitted", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Staged upload purge failed; it runs again later: {}", e.getMessage());
        }
    }
}
