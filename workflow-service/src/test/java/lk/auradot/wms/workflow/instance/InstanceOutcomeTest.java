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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET /instances/{id}/outcome: what document-service asks when a request it sent a document to is over. */
@WorkflowIntegrationTest
class InstanceOutcomeTest {

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
                        .content(mapper.writeValueAsString(Map.of("processKey", "expense-with-forms", "businessKey", "EXP-7",
                                "data", Map.of("amount", 500, "category", "travel", "purpose", "Taxi")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        instanceId = (String) mapper.readValue(body, Map.class).get("id");
    }

    @Test
    void aRunningRequestHasNoEndAndNoCompletedSteps() throws Exception {
        mvc.perform(get("/instances/{id}/outcome", instanceId).with(TestJwt.user("service-account-wms-document", "wms-service")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(instanceId))
                .andExpect(jsonPath("$.processKey").value("expense-with-forms"))
                .andExpect(jsonPath("$.processName").value("Expense with forms"))
                .andExpect(jsonPath("$.processVersion").value(1))
                .andExpect(jsonPath("$.businessKey").value("EXP-7"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.endStatus").doesNotExist())
                .andExpect(jsonPath("$.endedAt").doesNotExist())
                .andExpect(jsonPath("$.initiatorId").value("carol"))
                .andExpect(jsonPath("$.steps", hasSize(0)));
    }

    @Test
    void aFinishedRequestSaysHowItEndedAndWhoDecidedEachStep() throws Exception {
        String task = jdbc.sql("SELECT id FROM task WHERE instance_id = :i AND node_id = 'managerApproval'")
                .param("i", instanceId).query(String.class).single();
        mvc.perform(post("/tasks/{id}/complete", task).with(TestJwt.user("bob", "user"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"REJECT\"}"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/instances/{id}/outcome", instanceId).with(TestJwt.user("service-account-wms-document", "wms-service")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.endStatus").value("REJECTED"))
                .andExpect(jsonPath("$.endedAt").exists())
                .andExpect(jsonPath("$.steps", hasSize(1)))
                .andExpect(jsonPath("$.steps[0].nodeId").value("managerApproval"))
                .andExpect(jsonPath("$.steps[0].name", not(emptyOrNullString())))
                .andExpect(jsonPath("$.steps[0].completedBy").value("bob"))
                .andExpect(jsonPath("$.steps[0].outcome").value("REJECT"))
                .andExpect(jsonPath("$.steps[0].completedAt").exists());
    }

    @Test
    void onlyServicesAndAdministratorsMayAsk() throws Exception {
        mvc.perform(get("/instances/{id}/outcome", instanceId).with(TestJwt.user("alice", "wms-admin"))).andExpect(status().isOk());
        // carol started it, and still may not use this door: hers is GET /instances/{id}
        mvc.perform(get("/instances/{id}/outcome", instanceId).with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
        mvc.perform(get("/instances/{id}/outcome", "no-such-request").with(TestJwt.user("service-account-wms-document", "wms-service")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WF_INSTANCE_NOT_FOUND"));
    }
}
