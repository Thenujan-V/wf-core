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
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WorkflowIntegrationTest
class StartFormTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired DefinitionStore definitions;
    @Autowired StubForms forms;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        forms.reset();
        forms.publish("expense-claim", 1, "PUBLISHED", TestForms.load("expense-claim.json"));
        forms.publish("finance-review", 1, "PUBLISHED", TestForms.load("finance-review.json"));
        definitions.saveDraft("expense-with-forms", "Expense with forms", TestProcesses.load("expense-with-forms.json"), "bob");
        definitions.publish("expense-with-forms", "bob");
        definitions.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        definitions.publish("expense-approval", "bob");
    }

    private String start(String processKey, Map<String, Object> data) throws Exception {
        return mapper.writeValueAsString(Map.of("processKey", processKey, "businessKey", "EXP-F", "data", data));
    }

    private int instances() {
        return jdbc.sql("SELECT COUNT(*) FROM process_instance").query(Integer.class).single();
    }

    @Test
    void theCatalogSaysWhichProcessesHaveAStartForm() throws Exception {
        mvc.perform(get("/catalog").with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$[?(@.key=='expense-with-forms')].startForm.key").value("expense-claim"))
                .andExpect(jsonPath("$[?(@.key=='expense-with-forms')].startForm.version").value(1))
                .andExpect(jsonPath("$[?(@.key=='expense-approval')].startForm").value(org.hamcrest.Matchers.contains(nullValue())));
    }

    @Test
    void startDataGoesThroughFormServiceFirst() throws Exception {
        mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(start("expense-with-forms", Map.of("amount", 500, "category", "meals", "purpose", "Lunch"))))
                .andExpect(status().isCreated());

        assertThat(forms.submissions()).singleElement().satisfies(s -> {
            assertThat(s.formKey()).isEqualTo("expense-claim");
            assertThat(s.version()).isEqualTo(1);
            assertThat(s.submittedBy()).isEqualTo("carol");
            assertThat(s.contextRef()).isEqualTo("start:expense-with-forms");
            assertThat(s.access()).isEmpty();
        });
        String form = jdbc.sql("SELECT variables_json->>'$.form.purpose' FROM process_instance").query(String.class).single();
        assertThat(form).isEqualTo("Lunch");
    }

    @Test
    void invalidStartDataIsExplainedAndNothingStarts() throws Exception {
        forms.rejectNext(List.of(Map.of("field", "purpose", "code", "REQUIRED", "message", "Purpose is required")));

        mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(start("expense-with-forms", Map.of("amount", 500))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("purpose"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        assertThat(instances()).isZero();
    }

    @Test
    void formServiceDownMeansTryAgainLaterAndNothingStarts() throws Exception {
        forms.failing(true);

        mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(start("expense-with-forms", Map.of("amount", 500))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WF_FORM_SERVICE_UNAVAILABLE"));
        assertThat(instances()).isZero();
    }

    @Test
    void processesWithoutAStartFormStillTakeFreeData() throws Exception {
        mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(start("expense-approval", Map.of("amount", 20000))))
                .andExpect(status().isCreated());

        assertThat(forms.submissions()).isEmpty();
    }
}
