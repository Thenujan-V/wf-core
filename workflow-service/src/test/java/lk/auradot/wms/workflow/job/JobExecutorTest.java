package lk.auradot.wms.workflow.job;

import lk.auradot.wms.engine.runtime.Task;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import lk.auradot.wms.workflow.instance.WorkflowCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@WorkflowIntegrationTest
class JobExecutorTest {

    static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired WorkflowCommands commands;
    @Autowired JobClaims claims;
    @Autowired JobPoller poller;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        for (String key : List.of("wait-then-review", "wait-then-manager")) {
            definitions.saveDraft(key, key, TestProcesses.load(key + ".json"), "bob");
            definitions.publish(key, "bob");
        }
    }

    private List<Task> openTasks(String instanceId) {
        return commands.read(instanceId, (p, s) -> s.openTasks());
    }

    @Test
    void dueTimersFireAndFutureOnesWait() {
        String id = commands.start("wait-then-review", "carol", Map.of(), null);

        assertThat(poller.pollOnce()).as("not due yet").isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(openTasks(id)).extracting(Task::nodeId).containsExactly("review");
        assertThat(jdbc.sql("SELECT status, locked_by FROM job WHERE instance_id = :id").param("id", id)
                .query((rs, n) -> rs.getString(1) + "/" + rs.getString(2)).single()).isEqualTo("DONE/null");
    }

    @Test
    void concurrentReplicasClaimDisjointJobs() throws Exception {
        for (int i = 0; i < 10; i++) {
            commands.start("wait-then-review", "carol", Map.of(), null);
        }
        clock.advance(Duration.ofHours(2));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<List<ClaimedJob>>> results = new ArrayList<>();
        for (String owner : List.of("replica-a", "replica-b")) {
            results.add(pool.submit(() -> {
                go.await();
                return claims.claim(owner, 10, LEASE, clock.instant());
            }));
        }
        go.countDown();
        List<ClaimedJob> a = results.get(0).get();
        List<ClaimedJob> b = results.get(1).get();
        pool.shutdown();

        Set<String> ids = new HashSet<>();
        a.forEach(job -> ids.add(job.jobId()));
        b.forEach(job -> ids.add(job.jobId()));
        assertThat(a.size() + b.size()).isEqualTo(10);
        assertThat(ids).hasSize(10);
    }

    @Test
    void aLeaseBlocksOtherReplicasUntilItExpires() {
        commands.start("wait-then-review", "carol", Map.of(), null);
        clock.advance(Duration.ofHours(2));

        assertThat(claims.claim("replica-a", 10, LEASE, clock.instant())).hasSize(1);
        assertThat(claims.claim("replica-b", 10, LEASE, clock.instant())).isEmpty();
        clock.advance(LEASE.plusSeconds(1));
        assertThat(claims.claim("replica-b", 10, LEASE, clock.instant())).hasSize(1);
    }

    @Test
    void aFailedRunKeepsItsLeaseAndIsRetriedLater() {
        String id = commands.start("wait-then-manager", "carol", Map.of(), null);
        clock.advance(Duration.ofHours(2));
        directory.failing(true);

        assertThat(poller.pollOnce()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT status FROM job WHERE instance_id = :id").param("id", id)
                .query(String.class).single()).as("not DEAD").isEqualTo("PENDING");
        directory.failing(false);
        assertThat(poller.pollOnce()).as("still leased").isZero();
        clock.advance(Duration.ofMinutes(6));
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(openTasks(id)).singleElement().satisfies(t -> assertThat(t.assigneeId()).isEqualTo("bob"));
    }

    @Test
    void aJobWhoseLeaseExpiredWhileItsBatchWaitedIsNotRunAgain() {
        commands.start("wait-then-review", "carol", Map.of(), null);
        commands.start("wait-then-review", "carol", Map.of(), null);
        clock.advance(Duration.ofHours(2));
        List<ClaimedJob> ran = new ArrayList<>();
        JobRunner slowRunner = new JobRunner(null, null, null) {
            @Override
            public void run(ClaimedJob job) {
                ran.add(job);
                // The first job is so slow that the batch lease expires and another replica takes the rest.
                clock.advance(LEASE.plusSeconds(1));
                claims.claim("replica-b", 10, LEASE, clock.instant());
            }
        };
        JobPoller replicaA = new JobPoller(claims, slowRunner, new JobProperties(true, Duration.ofSeconds(1), 10, LEASE), clock);

        assertThat(replicaA.pollOnce()).isEqualTo(2);

        assertThat(ran).hasSize(1);
    }

    @Test
    void aFailedRunRecordsWhyForTheAdminMonitor() {
        String id = commands.start("wait-then-manager", "carol", Map.of(), null);
        clock.advance(Duration.ofHours(2));
        directory.failing(true);

        poller.pollOnce();

        assertThat(jdbc.sql("SELECT CONCAT(status, '/', COALESCE(last_error, '-')) FROM job WHERE instance_id = :id")
                .param("id", id).query(String.class).single()).startsWith("PENDING/").contains("directory");
    }
}
