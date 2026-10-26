package lk.auradot.wms.workflow.stats;

import lk.auradot.wms.workflow.InstanceFixture;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestJwt;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §9 dashboard: throughput, average cycle time, SLA breaches. "Now" is 2026-09-28T09:00Z. */
@WorkflowIntegrationTest
class StatsApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    InstanceFixture fixture;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        clock.set(MutableClock.DEFAULT);
        fixture = new InstanceFixture(jdbc);
        fixture.definition("expense-approval", "Expense approval");
        fixture.definition("leave-request", "Leave request");
    }

    void seed() {
        // Finished in the week: one day and two hours, so 46800 seconds on average.
        fixture.instance("e1", "expense-approval", "EXP-1", "COMPLETED", "APPROVED", "carol", "2026-09-26T10:00:00Z", "2026-09-27T10:00:00Z");
        fixture.instance("e2", "expense-approval", "EXP-2", "COMPLETED", "REJECTED", "carol", "2026-09-27T08:00:00Z", "2026-09-27T10:00:00Z");
        fixture.instance("e3", "expense-approval", "EXP-3", "ACTIVE", null, "bob", "2026-09-28T08:00:00Z", null);
        // Started long before the week, cancelled in it.
        fixture.instance("e4", "expense-approval", "EXP-4", "CANCELLED", null, "bob", "2026-09-01T08:00:00Z", "2026-09-27T09:00:00Z");
        fixture.instance("l1", "leave-request", "LV-1", "SUSPENDED", null, "dave", "2026-09-25T23:30:00Z", null);
        // Entirely before the week.
        fixture.instance("old", "expense-approval", "EXP-0", "COMPLETED", "APPROVED", "carol", "2026-08-01T08:00:00Z", "2026-08-02T08:00:00Z");
        fixture.breach("e1", "2026-09-26T20:00:00Z");
        fixture.breach("l1", "2026-09-27T01:00:00Z");
        fixture.breach("old", "2026-08-01T20:00:00Z");
    }

    ResultActions stats(String... params) throws Exception {
        var request = get("/stats").with(TestJwt.user("carol", "user"));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void countsWhatStartedAndEndedInThePeriod() throws Exception {
        seed();

        stats("days", "7").andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-22T00:00:00Z"))
                .andExpect(jsonPath("$.to").value("2026-09-28T09:00:00Z"))
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.totals.started").value(4))
                .andExpect(jsonPath("$.totals.completed").value(2))
                .andExpect(jsonPath("$.totals.cancelled").value(1))
                .andExpect(jsonPath("$.totals.active").value(2))
                .andExpect(jsonPath("$.totals.averageCycleSeconds").value(46800.0))
                .andExpect(jsonPath("$.totals.slaBreaches").value(2));
    }

    @Test
    void breaksTheFiguresDownByProcessBusiestFirst() throws Exception {
        seed();

        stats("days", "7")
                .andExpect(jsonPath("$.processes[*].processKey", contains("expense-approval", "leave-request")))
                .andExpect(jsonPath("$.processes[0].name").value("Expense approval"))
                .andExpect(jsonPath("$.processes[0].started").value(3))
                .andExpect(jsonPath("$.processes[0].completed").value(2))
                .andExpect(jsonPath("$.processes[0].cancelled").value(1))
                .andExpect(jsonPath("$.processes[0].active").value(1))
                .andExpect(jsonPath("$.processes[0].averageCycleSeconds").value(46800.0))
                .andExpect(jsonPath("$.processes[0].slaBreaches").value(1))
                .andExpect(jsonPath("$.processes[1].started").value(1))
                .andExpect(jsonPath("$.processes[1].completed").value(0))
                .andExpect(jsonPath("$.processes[1].active").value(1))
                .andExpect(jsonPath("$.processes[1].averageCycleSeconds").value(nullValue()))
                .andExpect(jsonPath("$.processes[1].slaBreaches").value(1));
    }

    @Test
    void givesEveryDayOfThePeriodEvenTheEmptyOnes() throws Exception {
        seed();

        stats("days", "7")
                .andExpect(jsonPath("$.daily[*].date", contains("2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25",
                        "2026-09-26", "2026-09-27", "2026-09-28")))
                .andExpect(jsonPath("$.daily[*].started", contains(0, 0, 0, 1, 1, 1, 1)))
                .andExpect(jsonPath("$.daily[*].completed", contains(0, 0, 0, 0, 0, 2, 0)));
    }

    @Test
    void daysAreTheViewersDaysNotUtcDays() throws Exception {
        seed();

        // UTC+5:30: 23:30Z on the 25th is already the 26th, and the week starts at 18:30Z on the 21st.
        stats("days", "7", "offsetMinutes", "330")
                .andExpect(jsonPath("$.from").value("2026-09-21T18:30:00Z"))
                .andExpect(jsonPath("$.offsetMinutes").value(330))
                .andExpect(jsonPath("$.daily[*].date", contains("2026-09-22", "2026-09-23", "2026-09-24", "2026-09-25",
                        "2026-09-26", "2026-09-27", "2026-09-28")))
                .andExpect(jsonPath("$.daily[*].started", contains(0, 0, 0, 0, 2, 1, 1)));
    }

    @Test
    void aPeriodWithNothingInItIsAllZerosNotAnError() throws Exception {
        stats("days", "3").andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.started").value(0))
                .andExpect(jsonPath("$.totals.completed").value(0))
                .andExpect(jsonPath("$.totals.active").value(0))
                .andExpect(jsonPath("$.totals.averageCycleSeconds").value(nullValue()))
                .andExpect(jsonPath("$.totals.slaBreaches").value(0))
                .andExpect(jsonPath("$.processes", hasSize(0)))
                .andExpect(jsonPath("$.daily", hasSize(3)));
    }

    @Test
    void thePeriodAndTheOffsetAreBounded() throws Exception {
        stats().andExpect(jsonPath("$.days").value(30)).andExpect(jsonPath("$.daily", hasSize(30)));
        stats("days", "0").andExpect(jsonPath("$.days").value(1));
        stats("days", "99999").andExpect(jsonPath("$.days").value(366));
        stats("offsetMinutes", "99999").andExpect(jsonPath("$.offsetMinutes").value(840));
        stats("offsetMinutes", "-99999").andExpect(jsonPath("$.offsetMinutes").value(-840));
    }

    @Test
    void everySignedInPersonMaySeeItButNobodyElse() throws Exception {
        stats().andExpect(status().isOk());
        mvc.perform(get("/stats")).andExpect(status().isUnauthorized());
    }
}
