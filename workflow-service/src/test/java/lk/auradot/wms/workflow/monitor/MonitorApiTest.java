package lk.auradot.wms.workflow.monitor;

import lk.auradot.wms.workflow.InstanceFixture;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §9 admin/monitor: every request, whoever started it. */
@WorkflowIntegrationTest
class MonitorApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    InstanceFixture fixture;

    @BeforeEach
    void seed() {
        TestDb.clear(jdbc);
        fixture = new InstanceFixture(jdbc);
        fixture.definition("expense-approval", "Expense approval");
        fixture.definition("leave-request", "Leave request");
        fixture.instance("i1", "expense-approval", "EXP-1", "ACTIVE", null, "carol", "2026-09-25T10:00:00Z", null);
        fixture.instance("i2", "expense-approval", "EXP-2", "COMPLETED", "APPROVED", "carol", "2026-09-26T10:00:00Z", "2026-09-27T10:00:00Z");
        fixture.instance("i3", "leave-request", "LV-1", "SUSPENDED", null, "dave", "2026-09-27T10:00:00Z", null);
        fixture.instance("i4", "leave-request", "100%_done", "CANCELLED", null, "bob", "2026-09-24T10:00:00Z", "2026-09-24T11:00:00Z");
        fixture.instance("i5", "expense-approval", null, "ACTIVE", null, "bob", "2026-09-28T08:00:00Z", null);
    }

    ResultActions search(String... params) throws Exception {
        var request = get("/admin/instances").with(TestJwt.user("alice", "wms-admin"));
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    @Test
    void anAdministratorSeesEveryonesRequestsNewestFirst() throws Exception {
        search().andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[*].id", contains("i5", "i3", "i2", "i1", "i4")))
                .andExpect(jsonPath("$.items[2].processKey").value("expense-approval"))
                .andExpect(jsonPath("$.items[2].businessKey").value("EXP-2"))
                .andExpect(jsonPath("$.items[2].status").value("COMPLETED"))
                .andExpect(jsonPath("$.items[2].endStatus").value("APPROVED"))
                .andExpect(jsonPath("$.items[2].initiatorId").value("carol"))
                .andExpect(jsonPath("$.items[2].startedAt").value("2026-09-26T10:00:00Z"))
                .andExpect(jsonPath("$.items[2].endedAt").value("2026-09-27T10:00:00Z"));
    }

    @Test
    void theListCanBeNarrowedByStatusProcessAndReference() throws Exception {
        search("status", "ACTIVE").andExpect(jsonPath("$.items[*].id", contains("i5", "i1"))).andExpect(jsonPath("$.total").value(2));
        search("processKey", "leave-request").andExpect(jsonPath("$.items[*].id", contains("i3", "i4")));
        search("q", "EXP").andExpect(jsonPath("$.items[*].id", contains("i2", "i1")));
        search("q", "i3").andExpect(jsonPath("$.items[*].id", contains("i3")));
        search("status", "ACTIVE", "processKey", "expense-approval", "q", "EXP-1").andExpect(jsonPath("$.items[*].id", contains("i1")));
        search("status", "", "processKey", " ", "q", "").andExpect(jsonPath("$.total").value(5));
    }

    @Test
    void searchTextIsTakenLiterally() throws Exception {
        search("q", "100%").andExpect(jsonPath("$.items[*].id", contains("i4")));
        search("q", "%").andExpect(jsonPath("$.items", hasSize(0)));
        search("q", "EXP__").andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void pagesAreBounded() throws Exception {
        search("size", "2", "page", "1").andExpect(jsonPath("$.items[*].id", contains("i2", "i1")))
                .andExpect(jsonPath("$.total").value(5)).andExpect(jsonPath("$.size").value(2)).andExpect(jsonPath("$.page").value(1));
        search("size", "500").andExpect(jsonPath("$.size").value(100));
        search("size", "0", "page", "-3").andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void anUnknownStatusIsRefusedNotIgnored() throws Exception {
        search("status", "RUNNING").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WF_STATUS_INVALID"));
    }

    @Test
    void onlyAdministratorsMaySeeEveryonesRequests() throws Exception {
        mvc.perform(get("/admin/instances").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/instances").with(TestJwt.user("bob", "process-designer"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/instances")).andExpect(status().isUnauthorized());
    }

    @Test
    void aDeadJobSaysWhichRequestItBelongsTo() throws Exception {
        fixture.deadJob("j1", "i1", "The webhook answered 500");

        mvc.perform(get("/jobs").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("j1"))
                .andExpect(jsonPath("$[0].instanceId").value("i1"))
                .andExpect(jsonPath("$[0].processKey").value("expense-approval"))
                .andExpect(jsonPath("$[0].businessKey").value("EXP-1"))
                .andExpect(jsonPath("$[0].lastError").value("The webhook answered 500"));
    }
}
