package lk.auradot.wms.form.definition;

import lk.auradot.wms.form.FormIntegrationTest;
import lk.auradot.wms.form.TestDb;
import lk.auradot.wms.form.TestForms;
import lk.auradot.wms.form.TestJwt;
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

@FormIntegrationTest
class FormDefinitionApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
    }

    private ResultActions saveDraft(String key, Map<String, Object> definition, String user, String... roles) throws Exception {
        return mvc.perform(put("/definitions/" + key + "/draft").with(TestJwt.user(user, roles))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("name", "Expense claim", "definition", definition))));
    }

    @Test
    void designersSaveValidateAndPublishVersionsEveryoneCanRead() throws Exception {
        saveDraft("expense-claim", TestForms.load("expense-claim.json"), "bob", "process-designer")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Expense claim"));
        mvc.perform(post("/definitions/expense-claim/validate").with(TestJwt.user("bob", "process-designer")))
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/definitions/expense-claim/publish").with(TestJwt.user("bob", "process-designer")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/definitions/expense-claim/versions").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(jsonPath("$[0].version").value(1));

        mvc.perform(get("/definitions/expense-claim/versions/1").with(TestJwt.user("carol", "user")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.definition.fields", hasSize(6)));
        mvc.perform(get("/definitions/expense-claim/versions/9").with(TestJwt.user("carol", "user")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FORM_VERSION_NOT_FOUND"));
    }

    @Test
    void invalidFormsAreExplainedNotPublished() throws Exception {
        saveDraft("broken", Map.of("fields", List.of(Map.of("key", "a", "type", "select", "label", "A"))),
                "bob", "process-designer").andExpect(status().isOk());

        mvc.perform(post("/definitions/broken/validate").with(TestJwt.user("bob", "process-designer")))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0].field").value("a"))
                .andExpect(jsonPath("$.errors[0].code").value("OPTIONS_MISSING"));
        mvc.perform(post("/definitions/broken/publish").with(TestJwt.user("bob", "process-designer")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_DEFINITION_INVALID"))
                .andExpect(jsonPath("$.errors[0].code").value("OPTIONS_MISSING"));
    }

    @Test
    void onlyDesignersManageForms() throws Exception {
        saveDraft("expense-claim", TestForms.load("expense-claim.json"), "carol", "user").andExpect(status().isForbidden());
        mvc.perform(get("/definitions").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
        mvc.perform(post("/definitions/expense-claim/retire").with(TestJwt.user("carol", "user"))).andExpect(status().isForbidden());
    }

    @Test
    void keysAreValidatedAndRetiredFormsStayReadable() throws Exception {
        saveDraft("Bad Key", TestForms.load("expense-claim.json"), "bob", "process-designer")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FORM_KEY_INVALID"));
        saveDraft("expense-claim", TestForms.load("expense-claim.json"), "bob", "process-designer");
        mvc.perform(post("/definitions/expense-claim/publish").with(TestJwt.user("bob", "process-designer")));

        mvc.perform(post("/definitions/expense-claim/retire").with(TestJwt.user("alice", "wms-admin")))
                .andExpect(status().isNoContent());

        mvc.perform(get("/definitions").with(TestJwt.user("bob", "process-designer")))
                .andExpect(jsonPath("$[0].status").value("RETIRED"));
        mvc.perform(get("/definitions/expense-claim/versions/1").with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void libraryAdminsMayListFormsToChooseDocumentTypeFieldsButNotChangeThem() throws Exception {
        saveDraft("expense-claim", TestForms.load("expense-claim.json"), "bob", "process-designer").andExpect(status().isOk());
        mvc.perform(post("/definitions/expense-claim/publish").with(TestJwt.user("bob", "process-designer")))
                .andExpect(status().isCreated());

        mvc.perform(get("/definitions").with(TestJwt.user("dave", "document-admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].key").value("expense-claim"));
        mvc.perform(get("/definitions/expense-claim/versions").with(TestJwt.user("dave", "document-admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(1));
        saveDraft("expense-claim", TestForms.load("expense-claim.json"), "dave", "document-admin").andExpect(status().isForbidden());
        mvc.perform(get("/definitions/expense-claim/draft").with(TestJwt.user("dave", "document-admin"))).andExpect(status().isForbidden());
    }

    @Test
    void aFieldWidthIsKeptThroughSavePublishAndRead() throws Exception {
        Map<String, Object> definition = Map.of(
                "sections", List.of(Map.of("id", "main", "title", "Main", "columns", 4)),
                "fields", List.of(Map.of("key", "a", "type", "text", "label", "A", "section", "main", "span", 3)));

        saveDraft("wide", definition, "bob", "process-designer").andExpect(status().isOk());
        mvc.perform(post("/definitions/wide/publish").with(TestJwt.user("bob", "process-designer")))
                .andExpect(status().isCreated());

        mvc.perform(get("/definitions/wide/versions/1").with(TestJwt.user("carol", "user")))
                .andExpect(jsonPath("$.definition.sections[0].columns").value(4))
                .andExpect(jsonPath("$.definition.fields[0].span").value(3));
    }
}
