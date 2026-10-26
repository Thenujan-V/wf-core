package lk.auradot.wms.document.text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wms.document.text.backfill-enabled", havingValue = "true", matchIfMissing = true)
class TextBackfillScheduler {

    private static final Logger log = LoggerFactory.getLogger(TextBackfillScheduler.class);

    private final TextBackfill backfill;

    TextBackfillScheduler(TextBackfill backfill) {
        this.backfill = backfill;
    }

    @Scheduled(fixedDelayString = "${wms.document.text.backfill-interval:PT5M}")
    void run() {
        try {
            int done = backfill.run();
            if (done > 0) {
                log.info("Extracted the missing text of {} version(s)", done);
            }
        } catch (RuntimeException e) {
            log.warn("Text backfill failed; it runs again later: {}", e.getMessage());
        }
    }
}
