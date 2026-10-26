package lk.auradot.wms.document.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wms.document.uploads.purge-enabled", havingValue = "true", matchIfMissing = true)
class UploadPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(UploadPurgeScheduler.class);

    private final UploadPurge purge;

    UploadPurgeScheduler(UploadPurge purge) {
        this.purge = purge;
    }

    @Scheduled(fixedDelayString = "${wms.document.uploads.purge-interval:PT1H}")
    void run() {
        try {
            int purged = purge.purge();
            if (purged > 0) {
                log.info("Removed {} abandoned upload(s)", purged);
            }
        } catch (RuntimeException e) {
            log.warn("Upload purge failed; it runs again later: {}", e.getMessage());
        }
    }
}
