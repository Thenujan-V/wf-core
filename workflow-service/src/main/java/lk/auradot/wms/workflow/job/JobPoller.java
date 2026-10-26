package lk.auradot.wms.workflow.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** One polling round; a failed run keeps its lease so it is retried only after the lease expires. */
@Component
public class JobPoller {

    private static final Logger log = LoggerFactory.getLogger(JobPoller.class);

    private final JobClaims claims;
    private final JobRunner runner;
    private final JobProperties props;
    private final Clock clock;
    private final String owner = "workflow-" + UUID.randomUUID();

    public JobPoller(JobClaims claims, JobRunner runner, JobProperties props, Clock clock) {
        this.claims = claims;
        this.runner = runner;
        this.props = props;
        this.clock = clock;
    }

    public int pollOnce() {
        List<ClaimedJob> jobs = claims.claim(owner, props.batchSize(), props.lease(), clock.instant());
        for (ClaimedJob job : jobs) {
            if (!claims.renew(job.jobId(), owner, props.lease(), clock.instant())) {
                log.info("Lease on job {} expired before it could run; leaving it to its new owner", job.jobId());
                continue;
            }
            try {
                runner.run(job);
                claims.release(job.jobId(), owner);
            } catch (RuntimeException e) {
                claims.recordFailure(job.jobId(), owner, e.getMessage() != null ? e.getMessage() : e.getClass().getName());
                log.warn("Job {} of instance {} failed; it will be retried after its lease expires",
                        job.jobId(), job.instanceId(), e);
            }
        }
        return jobs.size();
    }
}
