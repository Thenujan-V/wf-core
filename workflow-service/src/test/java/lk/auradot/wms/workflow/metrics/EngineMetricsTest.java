package lk.auradot.wms.workflow.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.runtime.EngineEvent;
import lk.auradot.wms.workflow.InstanceFixture;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.StubForms;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestForms;
import lk.auradot.wms.workflow.TestJwt;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §10: "Engine metrics include instances started/completed, task cycle time, SLA breaches and dead jobs." */
@WorkflowIntegrationTest
class EngineMetricsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired DefinitionStore definitions;
    @Autowired StubForms forms;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry registry;
    @Autowired EngineMetrics metrics;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        forms.reset();
    }

    double count(String name) {
        return registry.get(name).counter().count();
    }

    @Test
    void countsRequestsStartedAndCompletedAndTimesTheirTasks() throws Exception {
        forms.publish("receipt-claim", 1, "PUBLISHED", TestForms.load("receipt-claim.json"));
        definitions.saveDraft("receipt-review", "Receipt review", TestProcesses.load("receipt-review.json"), "bob");
        definitions.publish("receipt-review", "bob");
        double started = count("wms.instances.started");
        double completed = count("wms.instances.completed");
        long tasks = registry.get("wms.tasks.cycle").timer().count();
        double seconds = registry.get("wms.tasks.cycle").timer().totalTime(TimeUnit.SECONDS);

        String body = mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("processKey", "receipt-review", "data", Map.of("purpose", "Taxi")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String instanceId = (String) mapper.readValue(body, Map.class).get("id");
        String review = jdbc.sql("SELECT id FROM task WHERE instance_id = :i").param("i", instanceId).query(String.class).single();
        assertThat(count("wms.instances.started") - started).isEqualTo(1);
        assertThat(count("wms.instances.completed") - completed).isZero();

        clock.advance(Duration.ofHours(2));
        mvc.perform(post("/tasks/{id}/complete", review).with(TestJwt.user("bob", "user")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"OK\"}")).andExpect(status().isNoContent());

        assertThat(count("wms.instances.completed") - completed).isEqualTo(1);
        assertThat(registry.get("wms.tasks.cycle").timer().count() - tasks).isEqualTo(1);
        assertThat(registry.get("wms.tasks.cycle").timer().totalTime(TimeUnit.SECONDS) - seconds).isEqualTo(7200.0);
    }

    @Test
    void countsSlaBreachesAndCancellations() {
        double breaches = count("wms.sla.breaches");
        double cancelled = count("wms.instances.cancelled");

        metrics.record(List.of(), List.of(new EngineEvent.TaskOverdue("t-1", "bob", BreachAction.NOTIFY),
                new EngineEvent.InstanceCancelled("alice")), clock.instant());

        assertThat(count("wms.sla.breaches") - breaches).isEqualTo(1);
        assertThat(count("wms.instances.cancelled") - cancelled).isEqualTo(1);
    }

    @Test
    void whatWasRolledBackIsNotCounted() {
        double cancelled = count("wms.instances.cancelled");

        tx.executeWithoutResult(status -> {
            metrics.record(List.of(), List.of(new EngineEvent.InstanceCancelled("alice")), clock.instant());
            status.setRollbackOnly();
        });
        assertThat(count("wms.instances.cancelled") - cancelled).isZero();

        tx.executeWithoutResult(status ->
                metrics.record(List.of(), List.of(new EngineEvent.InstanceCancelled("alice")), clock.instant()));
        assertThat(count("wms.instances.cancelled") - cancelled).isEqualTo(1);
    }

    @Test
    void showsHowManyJobsAreDeadRightNow() {
        assertThat(registry.get("wms.jobs.dead").gauge().value()).isZero();
        InstanceFixture fixture = new InstanceFixture(jdbc);
        fixture.definition("expense-approval", "Expense approval");
        fixture.instance("i1", "expense-approval", "EXP-1", "ACTIVE", null, "carol", "2026-09-25T10:00:00Z", null);
        fixture.deadJob("j1", "i1", "The webhook answered 500");
        fixture.deadJob("j2", "i1", "The webhook answered 500");

        assertThat(registry.get("wms.jobs.dead").gauge().value()).isEqualTo(2);
    }

    @Test
    void aMeterThatFailsDoesNotFailTheChangeThatWasAlreadySaved() {
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();

        EngineMetrics.runAll(List.<Runnable>of(() -> {
            throw new IllegalStateException("the meter broke");
        }, ran::incrementAndGet));

        assertThat(ran.get()).as("the meters after the broken one are still updated").isEqualTo(1);
    }
}
