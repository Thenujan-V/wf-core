package lk.auradot.wms.workflow.job;

import lk.auradot.wms.workflow.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Lease-based job claiming (spec §4.4). SKIP LOCKED lets several replicas poll at once without ever taking
 * the same job; the lease stops a job from being run again while its owner is still working on it.
 */
@Repository
public class JobClaims {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JobClaims(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        // READ COMMITTED: claiming must not gap-lock the due-job index against commands inserting new jobs.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public List<ClaimedJob> claim(String owner, int limit, Duration lease, Instant now) {
        return tx.execute(status -> {
            List<ClaimedJob> jobs = jdbc.sql("""
                            SELECT id, instance_id FROM job
                            WHERE status = 'PENDING' AND due_at <= :now AND (locked_until IS NULL OR locked_until < :now)
                            ORDER BY due_at LIMIT :limit FOR UPDATE SKIP LOCKED""")
                    .param("now", Times.toDb(now)).param("limit", limit)
                    .query((rs, n) -> new ClaimedJob(rs.getString(1), rs.getString(2)))
                    .list();
            if (!jobs.isEmpty()) {
                jdbc.sql("UPDATE job SET locked_by = :owner, locked_until = :until WHERE id IN (:ids)")
                        .param("owner", owner).param("until", Times.toDb(now.plus(lease)))
                        .param("ids", jobs.stream().map(ClaimedJob::jobId).toList())
                        .update();
            }
            return jobs;
        });
    }

    public void release(String jobId, String owner) {
        jdbc.sql("UPDATE job SET locked_by = NULL, locked_until = NULL WHERE id = :id AND locked_by = :owner")
                .param("id", jobId).param("owner", owner).update();
    }

    /**
     * Extends the lease just before a job runs. False when the lease has already expired (another replica may have
     * claimed the job meanwhile), in which case the job must not run here.
     */
    public boolean renew(String jobId, String owner, Duration lease, Instant now) {
        return jdbc.sql("""
                        UPDATE job SET locked_until = :until
                        WHERE id = :id AND locked_by = :owner AND locked_until > :now""")
                .param("until", Times.toDb(now.plus(lease))).param("id", jobId).param("owner", owner)
                .param("now", Times.toDb(now))
                .update() == 1;
    }

    /** Keeps the reason a run failed visible to administrators; status and lease stay as they are. */
    public void recordFailure(String jobId, String owner, String error) {
        jdbc.sql("UPDATE job SET last_error = :error WHERE id = :id AND locked_by = :owner")
                .param("error", error.length() <= 2000 ? error : error.substring(0, 2000))
                .param("id", jobId).param("owner", owner)
                .update();
    }
}
