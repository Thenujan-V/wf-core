package lk.auradot.wms.form.submission;

import lk.auradot.wms.form.FormIntegrationTest;
import lk.auradot.wms.form.TestDb;
import lk.auradot.wms.form.TestForms;
import lk.auradot.wms.form.TestJwt;
import lk.auradot.wms.form.definition.FormStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@FormIntegrationTest
class SubmissionApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired FormStore forms;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        forms.saveDraft("expense-claim", "Expense claim", TestForms.load("expense-claim.json"), "bob");
        forms.publish("expense-claim", "bob");
    }

    private static Map<String, Object> claim(Object amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("amount", amount);
        data.put("category", "travel");
        data.put("purpose", "Client visit");
        return data;
    }

    private String body(Map<String, Object> request) {
        return mapper.writeValueAsString(request);
    }

    private int submissions() {
        return jdbc.sql("SELECT COUNT(*) FROM form_submission").query(Integer.class).single();
    }

    @Test
    void anyoneCanDryRunValidation() throws Exception {
        mvc.perform(post("/validate").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", claim(20000)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("justification"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(post("/validate").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", claim(500)))))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.data.amount").value(500));
        org.assertj.core.api.Assertions.assertThat(submissions()).isZero();
    }

    @Test
    void theWorkflowServiceStoresValidSubmissions() throws Exception {
        Map<String, Object> data = claim(500);
        data.put("justification", "not needed: hidden below 10,000");
        String created = mvc.perform(post("/submissions").with(TestJwt.user("svc", "wms-service"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", data,
                                "submittedBy", "carol", "contextRef", "instance:i1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.formVersion").value(1))
                .andExpect(jsonPath("$.submittedBy").value("carol"))
                .andExpect(jsonPath("$.data.justification").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = (String) mapper.readValue(created, Map.class).get("id");

        mvc.perform(get("/submissions/" + id).with(TestJwt.user("alice", "wms-admin")))
                .andExpect(jsonPath("$.contextRef").value("instance:i1"))
                .andExpect(jsonPath("$.data.purpose").value("Client visit"));
        mvc.perform(get("/submissions/nope").with(TestJwt.user("svc", "wms-service")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_SUBMISSION_NOT_FOUND"));
    }

    @Test
    void invalidOrReadOnlyChangesAreRejectedAndNothingIsStored() throws Exception {
        mvc.perform(post("/submissions").with(TestJwt.user("svc", "wms-service")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", claim(10.555), "submittedBy", "carol"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].code", hasItem("TOO_MANY_DECIMALS")));
        mvc.perform(post("/submissions").with(TestJwt.user("svc", "wms-service")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", claim(900), "submittedBy", "bob",
                                "access", Map.of("amount", "READONLY"), "baseline", Map.of("amount", 500)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("READ_ONLY"));
        org.assertj.core.api.Assertions.assertThat(submissions()).isZero();
    }

    @Test
    void onlyServicesStoreSubmissionsAndOnlyKnownVersionsAreAccepted() throws Exception {
        mvc.perform(post("/submissions").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", claim(500), "submittedBy", "carol"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/submissions/any").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
        mvc.perform(post("/submissions").with(TestJwt.user("svc", "wms-service")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 7, "data", claim(500), "submittedBy", "carol"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_VERSION_NOT_FOUND"));
        mvc.perform(post("/submissions").with(TestJwt.user("svc", "wms-service")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", claim(500)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void retiredVersionsStillAcceptSubmissions() throws Exception {
        forms.publish("expense-claim", "bob");
        forms.retire("expense-claim");

        mvc.perform(post("/submissions").with(TestJwt.user("svc", "wms-service")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("formKey", "expense-claim", "version", 1, "data", claim(500), "submittedBy", "carol"))))
                .andExpect(status().isCreated());
        org.assertj.core.api.Assertions.assertThat(List.of(submissions())).containsExactly(1);
    }
}
