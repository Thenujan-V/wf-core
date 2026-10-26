package lk.auradot.wms.workflow.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "wms.workflow.jobs.enabled", havingValue = "true", matchIfMissing = true)
class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobPoller poller;

    JobScheduler(JobPoller poller) {
        this.poller = poller;
    }

    @Scheduled(fixedDelayString = "${wms.workflow.jobs.poll-interval:PT1S}")
    void poll() {
        try {
            poller.pollOnce();
        } catch (RuntimeException e) {
            log.error("Job polling failed", e);
        }
    }
}
