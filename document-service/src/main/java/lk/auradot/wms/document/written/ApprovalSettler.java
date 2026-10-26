package lk.auradot.wms.document.written;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the settlement's two catch-up passes on a schedule (written-documents spec §8: tried again until it succeeds). */
@Component
@ConditionalOnProperty(name = "wms.document.approvals.settle-enabled", havingValue = "true", matchIfMissing = true)
class ApprovalSettler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSettler.class);

    private final ApprovalSettlement settlement;

    ApprovalSettler(ApprovalSettlement settlement) {
        this.settlement = settlement;
    }

    @Scheduled(fixedDelayString = "${wms.document.approvals.settle-interval:PT30S}")
    void run() {
        try {
            int finished = settlement.settleEnded();
            int found = settlement.sweepStale();
            if (finished + found > 0) {
                log.info("Settled {} approval round(s) that were waiting, and {} whose request never reported back", finished, found);
            }
        } catch (RuntimeException e) {
            log.warn("The approval settlement pass failed; it runs again later: {}", e.getMessage());
        }
    }
}
