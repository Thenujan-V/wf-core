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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §5: a field a step marks HIDDEN is not shown to people who only take part in that step. */
@WorkflowIntegrationTest
class HiddenFieldsTest {

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
        definitions.saveDraft("expense-finance-role", "Expense (finance role)", TestProcesses.load("expense-finance-role.json"), "bob");
        definitions.publish("expense-finance-role", "bob");
        String body = mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("processKey", "expense-finance-role", "data",
                                Map.of("amount", 20000, "category", "travel", "purpose", "Offsite", "justification", "Team")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        instanceId = (String) mapper.readValue(body, Map.class).get("id");
        String manager = jdbc.sql("SELECT id FROM task WHERE instance_id = :i AND node_id = 'managerApproval'")
                .param("i", instanceId).query(String.class).single();
        mvc.perform(post("/tasks/{id}/complete", manager).with(TestJwt.user("bob", "user"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"APPROVE\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void someoneWhoOnlyActsInAStepThatHidesAFieldDoesNotGetIt() throws Exception {
        mvc.perform(get("/instances/{id}", instanceId).with(TestJwt.user("frank", "finance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variables.form.amount").value(20000))
                .andExpect(jsonPath("$.variables.form.purpose").value("Offsite"))
                .andExpect(jsonPath("$.variables.form.category").doesNotExist());
    }

    @Test
    void theRequesterAdminsAndPeopleInOtherStepsStillSeeIt() throws Exception {
        for (var who : List.of(TestJwt.user("carol", "user"), TestJwt.user("bob", "user"), TestJwt.user("alice", "wms-admin"))) {
            mvc.perform(get("/instances/{id}", instanceId).with(who))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.variables.form.category").value("travel"));
        }
    }

    @Test
    void takingPartInAStepThatShowsTheFieldIsEnough() throws Exception {
        // bob approved as manager (a step without a form); holding the finance pool as well does not take it away.
        mvc.perform(get("/instances/{id}", instanceId).with(TestJwt.user("bob", "finance")))
                .andExpect(jsonPath("$.variables.form.category").value("travel"));
    }
}
