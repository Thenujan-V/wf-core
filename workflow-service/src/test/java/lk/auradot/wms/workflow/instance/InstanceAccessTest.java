package lk.auradot.wms.workflow.instance;

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
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WorkflowIntegrationTest
class InstanceAccessTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired DefinitionStore definitions;
    @Autowired StubForms forms;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    String instanceId;

    @BeforeEach
    void reset() throws Exception {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        forms.reset();
        forms.publish("expense-claim", 1, "PUBLISHED", TestForms.load("expense-claim.json"));
        forms.publish("finance-review", 1, "PUBLISHED", TestForms.load("finance-review.json"));
        definitions.saveDraft("expense-with-forms", "Expense with forms", TestProcesses.load("expense-with-forms.json"), "bob");
        definitions.publish("expense-with-forms", "bob");
        String body = mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("processKey", "expense-with-forms", "data",
                                Map.of("amount", 500, "category", "travel", "purpose", "Taxi")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        instanceId = (String) mapper.readValue(body, Map.class).get("id");
    }

    String managerTask() {
        return jdbc.sql("SELECT id FROM task WHERE instance_id = :i AND node_id = 'managerApproval'")
                .param("i", instanceId).query(String.class).single();
    }

    @Test
    void theInitiatorAndWhoeverHoldsAnOpenTaskMayAttachWhileItRuns() throws Exception {
        mvc.perform(get("/instances/{id}/access-check", instanceId).with(TestJwt.user("carol", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value(instanceId))
                .andExpect(jsonPath("$.processKey").value("expense-with-forms"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.canAttach").value(true));
        mvc.perform(get("/instances/{id}/access-check", instanceId).with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.canAttach").value(true));
        mvc.perform(get("/instances/{id}/access-check", instanceId).with(TestJwt.user("alice", "wms-admin")))
                .andExpect(jsonPath("$.canView").value(true)).andExpect(jsonPath("$.canAttach").value(true));
        mvc.perform(get("/instances/{id}/access-check", instanceId).with(TestJwt.user("eve", "user")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WF_INSTANCE_NOT_FOUND"));
    }

    @Test
    void aFinishedRequestStaysVisibleButTakesNoMoreAttachments() throws Exception {
        mvc.perform(post("/tasks/{id}/complete", managerTask()).with(TestJwt.user("bob", "user"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"REJECT\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/instances/{id}/access-check", instanceId).with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.canAttach").value(false));
        mvc.perform(get("/instances/{id}/access-check", instanceId).with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.canView").value(true))
                .andExpect(jsonPath("$.canAttach").value(false));
    }
}
