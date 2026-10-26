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

/** Spec §5: a field hidden in every step someone takes part in stays hidden in other steps' task forms too. */
@WorkflowIntegrationTest
class TaskFormHiddenFieldsTest {

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
        definitions.saveDraft("parallel-review", "Parallel review", TestProcesses.load("parallel-review.json"), "bob");
        definitions.publish("parallel-review", "bob");
        String body = mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("processKey", "parallel-review", "data",
                                Map.of("amount", 500, "category", "travel", "purpose", "Taxi")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        instanceId = (String) mapper.readValue(body, Map.class).get("id");
    }

    String task(String nodeId) {
        return jdbc.sql("SELECT id FROM task WHERE instance_id = :i AND node_id = :n").param("i", instanceId).param("n", nodeId)
                .query(String.class).single();
    }

    @Test
    void anotherStepsFormDoesNotRevealWhatTheReadersOwnStepsHide() throws Exception {
        mvc.perform(get("/tasks/{id}/form", task("managerReview")).with(TestJwt.user("frank", "finance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.purpose").value("Taxi"))
                .andExpect(jsonPath("$.values.category").doesNotExist());
    }

    @Test
    void theStepsOwnPeopleStillSeeWhatItShows() throws Exception {
        mvc.perform(get("/tasks/{id}/form", task("managerReview")).with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$.values.category").value("travel"));
        mvc.perform(get("/tasks/{id}/form", task("financeCheck")).with(TestJwt.user("frank", "finance")))
                .andExpect(jsonPath("$.values.category").doesNotExist());
    }
}
