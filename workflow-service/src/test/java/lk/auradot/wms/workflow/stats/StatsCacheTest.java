package lk.auradot.wms.workflow.stats;

import lk.auradot.wms.workflow.InstanceFixture;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestJwt;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Everyone sees the same figures, so they are worked out at most once per period every half minute. */
@WorkflowIntegrationTest
@TestPropertySource(properties = "wms.workflow.stats.cache-ttl=PT30S")
class StatsCacheTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired MutableClock clock;

    @AfterEach
    void restoreClock() {
        clock.set(MutableClock.DEFAULT);
    }

    ResultActions stats(String days, String who) throws Exception {
        return mvc.perform(get("/stats").param("days", days).with(TestJwt.user(who, "user")));
    }

    @Test
    void theFiguresForAPeriodAreReusedForHalfAMinuteByEveryone() throws Exception {
        TestDb.clear(jdbc);
        clock.set(MutableClock.DEFAULT);
        InstanceFixture fixture = new InstanceFixture(jdbc);
        fixture.definition("expense-approval", "Expense approval");
        fixture.instance("e1", "expense-approval", "EXP-1", "ACTIVE", null, "carol", "2026-09-28T08:00:00Z", null);
        stats("7", "carol").andExpect(jsonPath("$.totals.started").value(1));

        fixture.instance("e2", "expense-approval", "EXP-2", "ACTIVE", null, "bob", "2026-09-28T08:30:00Z", null);

        stats("7", "bob").andExpect(jsonPath("$.totals.started").value(1));  // the answer carol got
        stats("3", "bob").andExpect(jsonPath("$.totals.started").value(2));  // another period is worked out afresh
        clock.advance(Duration.ofSeconds(31));
        stats("7", "carol").andExpect(jsonPath("$.totals.started").value(2));
    }
}
