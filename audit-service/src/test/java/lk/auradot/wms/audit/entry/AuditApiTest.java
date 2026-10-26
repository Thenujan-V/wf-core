package lk.auradot.wms.audit.entry;

import lk.auradot.wms.audit.AuditAdminDb;
import lk.auradot.wms.audit.AuditIntegrationTest;
import lk.auradot.wms.audit.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §8: "Query API filters by actor, entity and date range". */
@AuditIntegrationTest
class AuditApiTest {

    @Autowired MockMvc mvc;
    @Autowired AuditStore store;
    @Autowired AuditAdminDb admin;

    @BeforeEach
    void seed() {
        admin.clear();
        add("m-1", "workflow.instance.started", "carol", "INSTANCE", "i-1", "2026-10-01T08:00:00Z");
        add("m-2", "workflow.task.completed", "bob", "TASK", "t-1", "2026-10-01T09:00:00Z");
        add("m-3", "document.deleted", "bob", "DOCUMENT", "d-1", "2026-10-01T10:00:00Z");
        add("m-4", "workflow.task.reminder", null, "TASK", "t-1", "2026-10-01T11:00:00Z");
    }

    void add(String messageId, String action, String actor, String entityType, String entityId, String at) {
        store.insert(new NewAuditEntry(messageId, action, actor, entityType, entityId,
                Map.of("messageId", messageId, "note", "about " + entityId), "corr-" + messageId, Instant.parse(at)), Instant.parse(at));
    }

    ResultActions entries(String query) throws Exception {
        return mvc.perform(get("/entries" + query).with(TestJwt.user("alice", "wms-admin")));
    }

    @Test
    void administratorsSeeTheLogNewestFirst() throws Exception {
        entries("").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[*].action", contains("workflow.task.reminder", "document.deleted",
                        "workflow.task.completed", "workflow.instance.started")))
                .andExpect(jsonPath("$.items[1].actorId").value("bob"))
                .andExpect(jsonPath("$.items[1].entityType").value("DOCUMENT"))
                .andExpect(jsonPath("$.items[1].entityId").value("d-1"))
                .andExpect(jsonPath("$.items[1].data.note").value("about d-1"))
                .andExpect(jsonPath("$.items[1].correlationId").value("corr-m-3"))
                .andExpect(jsonPath("$.items[1].occurredAt").value("2026-10-01T10:00:00Z"));
    }

    @Test
    void filtersByActorEntityAndAction() throws Exception {
        entries("?actor=bob").andExpect(jsonPath("$.total").value(2));
        entries("?entityType=task&entityId=t-1").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[*].action", contains("workflow.task.reminder", "workflow.task.completed")));
        entries("?action=document.deleted").andExpect(jsonPath("$.total").value(1));
        entries("?actor=nobody").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void theDateRangeIncludesItsStartAndExcludesItsEnd() throws Exception {
        entries("?from=2026-10-01T09:00:00Z&to=2026-10-01T10:00:00Z")
                .andExpect(jsonPath("$.items[*].action", contains("workflow.task.completed")));
        entries("?from=2026-10-01T10:00:00Z").andExpect(jsonPath("$.total").value(2));
        entries("?from=2026-10-01T10:00:00Z&to=2026-10-01T09:00:00Z")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("AUDIT_RANGE_INVALID"));
    }

    @Test
    void pagesAreBoundedAndReportTheTotal() throws Exception {
        entries("?size=3&page=1").andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.items[*].action", contains("workflow.instance.started")));
        entries("?size=500").andExpect(jsonPath("$.size").value(100));
        entries("?size=0&page=-3").andExpect(jsonPath("$.size").value(1)).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void onlyAdministratorsMayReadTheLog() throws Exception {
        mvc.perform(get("/entries").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
        mvc.perform(get("/entries").with(TestJwt.user("dave", "document-admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/entries")).andExpect(status().isUnauthorized());
    }
}
