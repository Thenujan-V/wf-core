package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestJwt;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WorkflowIntegrationTest
class DefinitionApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
    }

    private ResultActions saveDraft(String key, Map<String, Object> graph, String user, String... roles) throws Exception {
        return mvc.perform(put("/definitions/" + key + "/draft").with(TestJwt.user(user, roles))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "Expense approval", "graph", graph))));
    }

    @Test
    void designerSavesValidatesAndPublishesVersions() throws Exception {
        saveDraft("expense-approval", TestProcesses.load("expense-approval.json"), "bob", "process-designer")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Expense approval"))
                .andExpect(jsonPath("$.updatedBy").value("bob"));
        mvc.perform(post("/definitions/expense-approval/validate").with(TestJwt.user("bob", "process-designer")))
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/definitions/expense-approval/publish").with(TestJwt.user("bob", "process-designer")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(post("/definitions/expense-approval/publish").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/definitions/expense-approval/versions/1").with(TestJwt.user("bob", "process-designer")))
                .andExpect(jsonPath("$.graph.nodes", hasSize(7)));
        mvc.perform(get("/catalog").with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$[0].key").value("expense-approval"))
                .andExpect(jsonPath("$[0].version").value(2));
    }

    @Test
    void invalidProcessesAreExplainedNotPublished() throws Exception {
        saveDraft("broken", Map.of("nodes", List.of(Map.of("id", "s", "type", "START")), "edges", List.of()),
                "bob", "process-designer").andExpect(status().isOk());

        mvc.perform(post("/definitions/broken/validate").with(TestJwt.user("bob", "process-designer")))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].code").value("NO_END"));
        mvc.perform(post("/definitions/broken/publish").with(TestJwt.user("bob", "process-designer")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WF_DEFINITION_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_END"));
    }

    @Test
    void onlyDesignersAndAdminsManageDefinitions() throws Exception {
        saveDraft("expense-approval", TestProcesses.load("expense-approval.json"), "carol", "user")
                .andExpect(status().isForbidden());
        mvc.perform(get("/definitions").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
    }

    @Test
    void keysAreValidatedAndRetiredProcessesLeaveTheCatalog() throws Exception {
        saveDraft("Bad Key", TestProcesses.load("expense-approval.json"), "bob", "process-designer")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WF_KEY_INVALID"));
        saveDraft("expense-approval", TestProcesses.load("expense-approval.json"), "bob", "process-designer");
        mvc.perform(post("/definitions/expense-approval/publish").with(TestJwt.user("bob", "process-designer")));

        mvc.perform(post("/definitions/expense-approval/retire").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/catalog").with(TestJwt.user("carol", "user"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/definitions/expense-approval/versions/9").with(TestJwt.user("bob", "process-designer")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WF_VERSION_NOT_FOUND"));
    }
}
