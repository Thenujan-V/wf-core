package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.TestDb;
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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WorkflowIntegrationTest
class InstanceApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired DefinitionStore definitions;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        definitions.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        definitions.publish("expense-approval", "bob");
        definitions.saveDraft("wait-until-date", "Wait until date", TestProcesses.load("wait-until-date.json"), "bob");
        definitions.publish("wait-until-date", "bob");
    }

    /** Each start names its own request; the first one in a test is EXP-7. Reusing a key would replay that start. */
    private int nextKey = 7;

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private String start(String processKey, String user, Map<String, Object> data) throws Exception {
        String body = mvc.perform(post("/instances").with(TestJwt.user(user, "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("processKey", processKey, "businessKey", "EXP-" + nextKey++, "data", data))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return (String) mapper.readValue(body, Map.class).get("id");
    }

    private String onlyTaskId(String user, String view, String... roles) throws Exception {
        String body = mvc.perform(get("/tasks").param("view", view).with(TestJwt.user(user, roles)))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        return (String) ((Map<?, ?>) mapper.readValue(body, java.util.List.class).getFirst()).get("id");
    }

    @Test
    void aRequestFlowsThroughManagerAndFinanceInboxes() throws Exception {
        String id = start("expense-approval", "carol", Map.of("amount", 20000));

        mvc.perform(get("/tasks").param("view", "mine").with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$[0].nodeId").value("managerApproval"))
                .andExpect(jsonPath("$[0].businessKey").value("EXP-7"))
                .andExpect(jsonPath("$[0].outcomes", hasSize(2)));
        String managerTask = onlyTaskId("bob", "mine", "user");

        mvc.perform(post("/tasks/" + managerTask + "/complete").with(TestJwt.user("dave", "user"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "APPROVE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WF_NOT_ASSIGNEE"));
        mvc.perform(post("/tasks/" + managerTask + "/complete").with(TestJwt.user("bob", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("outcome", "APPROVE", "data", Map.of("comment", "Fine")))))
                .andExpect(status().isNoContent());

        String financeTask = onlyTaskId("alice", "group", "wms-admin");
        mvc.perform(post("/tasks/" + financeTask + "/claim").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(status().isNoContent());
        mvc.perform(post("/tasks/" + financeTask + "/complete").with(TestJwt.user("alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "APPROVE"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/instances").with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/instances/" + id).with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$.variables.form.comment").value("Fine"))
                .andExpect(jsonPath("$.tasks", hasSize(2)));
        mvc.perform(get("/instances/" + id + "/history").with(TestJwt.user("bob", "user")))
                .andExpect(jsonPath("$[*].type", hasItem("TaskCompleted")));
        mvc.perform(get("/instances/" + id).with(TestJwt.user("eve", "user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WF_INSTANCE_NOT_FOUND"));
    }

    @Test
    void aReplayedStartAnswersOkWithTheSameRequestAndServicesLookItUpByKey() throws Exception {
        String id = start("expense-approval", "carol", Map.of("amount", 1));

        mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("processKey", "expense-approval", "businessKey", "EXP-7",
                                "data", Map.of("amount", 1)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/instances").with(TestJwt.user("dave", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("processKey", "expense-approval", "businessKey", "EXP-7", "data", Map.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WF_BUSINESS_KEY_CONFLICT"));

        mvc.perform(get("/instances/lookup").param("businessKey", "EXP-7").with(TestJwt.user("svc", "wms-service")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/instances/lookup").param("businessKey", "NOPE").with(TestJwt.user("svc", "wms-service")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/instances/lookup").param("businessKey", "EXP-7").with(TestJwt.user("carol", "user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aServiceCancelsARequestItStartedWithAReason() throws Exception {
        String id = start("expense-approval", "carol", Map.of("amount", 1));

        mvc.perform(post("/instances/" + id + "/cancel").with(TestJwt.user("svc", "wms-service"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Withdrawn by carol"))))
                .andExpect(status().isNoContent());
        mvc.perform(post("/instances/" + id + "/cancel").with(TestJwt.user("svc", "wms-service")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WF_INSTANCE_NOT_ACTIVE"));
        mvc.perform(get("/instances/" + id + "/history").with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$[?(@.type == 'InstanceCancelled')].data.reason").value(hasItem("Withdrawn by carol")));
    }

    @Test
    void groupInboxFollowsDirectoryGroupsAndDelegationMovesTasks() throws Exception {
        start("expense-approval", "carol", Map.of("amount", 20000));
        String managerTask = onlyTaskId("bob", "mine", "user");

        mvc.perform(post("/tasks/" + managerTask + "/delegate").with(TestJwt.user("bob", "user"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("toUserId", "dave"))))
                .andExpect(status().isNoContent());

        mvc.perform(get("/tasks").param("view", "mine").with(TestJwt.user("bob", "user"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/tasks").param("view", "mine").with(TestJwt.user("dave", "user"))).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/tasks").param("view", "all").with(TestJwt.user("dave", "user"))).andExpect(status().isForbidden());
        mvc.perform(get("/tasks").param("view", "everything").with(TestJwt.user("dave", "user")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WF_VIEW_INVALID"));
    }

    @Test
    void administratorsCancelInstancesAndRetryDeadJobs() throws Exception {
        String expense = start("expense-approval", "carol", Map.of("amount", 1));
        mvc.perform(post("/instances/" + expense + "/cancel").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
        mvc.perform(post("/instances/" + expense + "/cancel").with(TestJwt.user("alice", "wms-admin"))).andExpect(status().isNoContent());
        mvc.perform(get("/instances/" + expense).with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$.instance.status").value("CANCELLED"));
        mvc.perform(get("/tasks").param("view", "mine").with(TestJwt.user("bob", "user"))).andExpect(jsonPath("$", hasSize(0)));

        start("wait-until-date", "carol", Map.of());
        String body = mvc.perform(get("/jobs").param("status", "DEAD").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].lastError").value(org.hamcrest.Matchers.containsString("form.resumeOn")))
                .andReturn().getResponse().getContentAsString();
        String jobId = (String) ((Map<?, ?>) mapper.readValue(body, java.util.List.class).getFirst()).get("id");
        mvc.perform(post("/jobs/" + jobId + "/retry").with(TestJwt.user("alice", "wms-admin"))).andExpect(status().isNoContent());
        mvc.perform(get("/jobs").param("status", "DEAD").with(TestJwt.user("alice", "wms-admin"))).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/jobs").param("status", "DEAD").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
    }

    /** Spec §4: retrying a dead job is audited, like the other administrator actions. */
    @Test
    @SuppressWarnings("unchecked")
    void retryingADeadJobIsAnnouncedWithWhoDidIt() throws Exception {
        start("wait-until-date", "carol", Map.of());
        String body = mvc.perform(get("/jobs").param("status", "DEAD").with(TestJwt.user("alice", "wms-admin")))
                .andReturn().getResponse().getContentAsString();
        String jobId = (String) ((Map<?, ?>) mapper.readValue(body, java.util.List.class).getFirst()).get("id");

        mvc.perform(post("/jobs/" + jobId + "/retry").with(TestJwt.user("alice", "wms-admin"))).andExpect(status().isNoContent());

        java.util.List<String> payloads = jdbc.sql("SELECT payload_json FROM outbox WHERE routing_key = 'workflow.job.retried'")
                .query(String.class).list();
        org.assertj.core.api.Assertions.assertThat(payloads).hasSize(1);
        Map<String, Object> event = mapper.readValue(payloads.getFirst(), Map.class);
        org.assertj.core.api.Assertions.assertThat(event).containsEntry("actorId", "alice");
        org.assertj.core.api.Assertions.assertThat((Map<String, Object>) event.get("data")).containsEntry("jobId", jobId);
    }
}
