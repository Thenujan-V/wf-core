package lk.auradot.wms.workflow.stats;

import io.micrometer.core.instrument.MeterRegistry;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestJwt;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import lk.auradot.wms.workflow.instance.WorkflowCommands;
import lk.auradot.wms.workflow.job.JobPoller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The other statistics tests write "TaskOverdue" into the history themselves. This one lets the engine miss a real
 * deadline, so renaming that event cannot silently empty the "Deadlines missed" figure.
 */
@WorkflowIntegrationTest
class SlaBreachIsCountedTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired WorkflowCommands commands;
    @Autowired JobPoller poller;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;
    @Autowired MeterRegistry registry;

    @Test
    void aTaskThatGoesPastItsDeadlineShowsOnTheDashboardAndInTheMetrics() throws Exception {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        definitions.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        definitions.publish("expense-approval", "bob");
        double before = registry.get("wms.sla.breaches").counter().count();

        commands.start("expense-approval", "carol", Map.of("amount", 500), null); // the manager has 48 hours
        clock.advance(Duration.ofHours(49));
        while (poller.pollOnce() > 0) {
            // the reminder, then the breach
        }

        mvc.perform(get("/stats").param("days", "7").with(TestJwt.user("carol", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.slaBreaches").value(1))
                .andExpect(jsonPath("$.processes[0].processKey").value("expense-approval"))
                .andExpect(jsonPath("$.processes[0].slaBreaches").value(1));
        assertThat(registry.get("wms.sla.breaches").counter().count() - before).isEqualTo(1);
        clock.set(MutableClock.DEFAULT);
    }
}
