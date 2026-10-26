package lk.auradot.wms.workflow.forms;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WorkflowIntegrationTest
class TaskFormTest {

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
                        .content(json(Map.of("processKey", "expense-with-forms", "data",
                                Map.of("amount", 20000, "category", "travel", "purpose", "Offsite", "justification", "Team")))))
                .andReturn().getResponse().getContentAsString();
        instanceId = (String) mapper.readValue(body, Map.class).get("id");
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private String taskId(String nodeId) {
        return jdbc.sql("SELECT id FROM task WHERE instance_id = :i AND node_id = :n").param("i", instanceId).param("n", nodeId)
                .query(String.class).single();
    }

    private String formValue(String key) {
        return jdbc.sql("SELECT variables_json->>'$.form.\"" + key + "\"' FROM process_instance WHERE id = :i")
                .param("i", instanceId).query(String.class).single();
    }

    private void approveAsManager(String comment) throws Exception {
        mvc.perform(post("/tasks/" + taskId("managerApproval") + "/complete").with(TestJwt.user("bob", "user"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "APPROVE", "comment", comment))))
                .andExpect(status().isNoContent());
    }

    @Test
    void commentsAreStoredPerTaskAndFormlessTasksNeedNoSubmission() throws Exception {
        approveAsManager("Within budget");

        assertThat(formValue("comment_" + taskId("managerApproval"))).isEqualTo("Within budget");
        assertThat(forms.submissions()).hasSize(1); // only the start form
    }

    @Test
    void theAssigneeGetsTheTaskFormWithoutHiddenValues() throws Exception {
        approveAsManager("ok");
        String finance = taskId("financeApproval");

        mvc.perform(get("/tasks").param("view", "group").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(jsonPath("$[0].formKey").value("finance-review"))
                .andExpect(jsonPath("$[0].formVersion").value(1));
        mvc.perform(get("/tasks/" + finance + "/form").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formKey").value("finance-review"))
                .andExpect(jsonPath("$.access.amount").value("READONLY"))
                .andExpect(jsonPath("$.values.amount").value(20000))
                .andExpect(jsonPath("$.values.category").doesNotExist());
        mvc.perform(get("/tasks/" + finance + "/form").with(TestJwt.user("eve", "user")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/tasks/" + taskId("managerApproval") + "/form").with(TestJwt.user("bob", "user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WF_TASK_FORM_NOT_FOUND"));
    }

    @Test
    void completingATaskFormSendsAccessAndBaselineAndKeepsReadOnlyValues() throws Exception {
        approveAsManager("ok");
        String finance = taskId("financeApproval");
        mvc.perform(post("/tasks/" + finance + "/claim").with(TestJwt.user("alice", "wms-admin"))).andExpect(status().isNoContent());

        mvc.perform(post("/tasks/" + finance + "/complete").with(TestJwt.user("alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("outcome", "APPROVE", "data", Map.of("costCentre", "FN-120", "amount", 1)))))
                .andExpect(status().isNoContent());

        SubmitForm sent = forms.submissions().getLast();
        assertThat(sent.formKey()).isEqualTo("finance-review");
        assertThat(sent.contextRef()).isEqualTo("task:" + finance);
        assertThat(sent.access()).containsEntry("amount", "READONLY").containsEntry("category", "HIDDEN");
        assertThat(sent.baseline()).containsEntry("amount", 20000).containsEntry("purpose", "Offsite");
        assertThat(formValue("costCentre")).isEqualTo("FN-120");
        assertThat(formValue("amount")).isEqualTo("20000");
    }

    @Test
    void invalidTaskDataIsExplainedAndTheTaskStaysOpen() throws Exception {
        approveAsManager("ok");
        String finance = taskId("financeApproval");
        mvc.perform(post("/tasks/" + finance + "/claim").with(TestJwt.user("alice", "wms-admin")));
        forms.rejectNext(List.of(Map.of("field", "costCentre", "code", "PATTERN_MISMATCH", "message", "Not a cost centre")));

        mvc.perform(post("/tasks/" + finance + "/complete").with(TestJwt.user("alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "APPROVE", "data", Map.of("costCentre", "x")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("costCentre"));
        assertThat(jdbc.sql("SELECT status FROM task WHERE id = :t").param("t", finance).query(String.class).single())
                .isEqualTo("CLAIMED");
    }

    @Test
    void refusedCompletionsNeverStoreASubmission() throws Exception {
        approveAsManager("ok");
        String finance = taskId("financeApproval");
        int before = forms.submissions().size();

        mvc.perform(post("/tasks/" + finance + "/complete").with(TestJwt.user("alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "APPROVE", "data", Map.of()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WF_TASK_NOT_CLAIMED"));
        mvc.perform(post("/tasks/" + finance + "/claim").with(TestJwt.user("alice", "wms-admin")));
        mvc.perform(post("/tasks/" + finance + "/complete").with(TestJwt.user("alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "MAYBE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WF_INVALID_OUTCOME"));
        mvc.perform(post("/tasks/" + finance + "/complete").with(TestJwt.user("dave", "user"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "APPROVE"))))
                .andExpect(status().isForbidden());

        assertThat(forms.submissions()).hasSize(before);
    }

    @Test
    void formServiceDownKeepsTheTaskOpen() throws Exception {
        approveAsManager("ok");
        String finance = taskId("financeApproval");
        mvc.perform(post("/tasks/" + finance + "/claim").with(TestJwt.user("alice", "wms-admin")));
        forms.failing(true);

        mvc.perform(post("/tasks/" + finance + "/complete").with(TestJwt.user("alice", "wms-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("outcome", "APPROVE", "data", Map.of("costCentre", "FN-120")))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WF_FORM_SERVICE_UNAVAILABLE"));
        assertThat(jdbc.sql("SELECT status FROM task WHERE id = :t").param("t", finance).query(String.class).single())
                .isEqualTo("CLAIMED");
    }

    @Autowired lk.auradot.wms.workflow.instance.TaskCompletion completion;

    @Test
    void aTaskWithoutAFormTakesNoDataInAProcessThatUsesForms() throws Exception {
        mvc.perform(post("/tasks/" + taskId("managerApproval") + "/complete").with(TestJwt.user("bob", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("outcome", "APPROVE", "data", Map.of("amount", 5)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WF_TASK_HAS_NO_FORM"));

        assertThat(formValue("amount")).isEqualTo("20000");
        assertThat(jdbc.sql("SELECT status FROM task WHERE id = :t").param("t", taskId("managerApproval"))
                .query(String.class).single()).isEqualTo("OPEN");
    }

    @Test
    void aTaskClaimedWhileARefusedCompletionWasInFlightIsNotCompletedWithUncheckedData() throws Exception {
        approveAsManager("ok");
        String finance = taskId("financeApproval");
        // The pre-check refused (not claimed); the task was then claimed before the engine command ran.
        mvc.perform(post("/tasks/" + finance + "/claim").with(TestJwt.user("alice", "wms-admin"))).andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> completion.completeRefused(finance, "alice", "APPROVE"))
                .isInstanceOfSatisfying(lk.auradot.wms.common.error.ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("WF_CONCURRENT_UPDATE"));
        assertThat(jdbc.sql("SELECT status FROM task WHERE id = :t").param("t", finance).query(String.class).single())
                .isEqualTo("CLAIMED");
    }

    @Autowired FormSubmissions submissions;

    @Test
    void onlyEditableFieldsAreMergedAndClearedOnesAreCleared() {
        String definitionId = jdbc.sql("SELECT definition_id FROM process_instance WHERE id = :i").param("i", instanceId)
                .query(String.class).single();

        Map<String, Object> merged = submissions.acceptTask(definitionId, "financeApproval", "t-x",
                Map.of("costCentre", "FN-120"), Map.of("amount", 20000, "category", "travel", "financeNote", "old note"), "alice");

        // Read-only and hidden values are unchanged by construction; writing them back could undo a parallel step.
        assertThat(merged).doesNotContainKeys("amount", "category");
        assertThat(merged).containsEntry("costCentre", "FN-120");
        // An editable field the person left empty must not keep its old value.
        assertThat(merged).containsKey("financeNote");
        assertThat(merged.get("financeNote")).isNull();
    }
}
