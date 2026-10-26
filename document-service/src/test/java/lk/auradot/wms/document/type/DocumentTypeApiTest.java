package lk.auradot.wms.document.type;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeForms;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.forms.FormRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static lk.auradot.wms.document.TestJwt.user;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class DocumentTypeApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired FakeForms forms;
    @Autowired JsonMapper json;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        forms.reset();
        forms.publish(new FormRef("invoice-meta", 1), Set.of("supplier", "amount"), Set.of("supplier"));
    }

    String create(String body) throws Exception {
        String response = mvc.perform(post("/document-types").with(user("dave", "document-admin"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asString();
    }

    @Test
    void libraryAdminsDefineTypesThatEveryoneCanList() throws Exception {
        create("{\"name\":\"Invoice\",\"form\":{\"key\":\"invoice-meta\",\"version\":1}}");
        create("{\"name\":\"Contract\"}");

        mvc.perform(get("/document-types").with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Contract"))
                .andExpect(jsonPath("$[1].form.key").value("invoice-meta"));
        mvc.perform(post("/document-types").with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Mine\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTypeMayOnlyUseAPublishedFormAndNamesAreUnique() throws Exception {
        mvc.perform(post("/document-types").with(user("dave", "document-admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Receipt\",\"form\":{\"key\":\"nope\",\"version\":1}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOC_TYPE_FORM_INVALID"));
        create("{\"name\":\"Invoice\"}");
        mvc.perform(post("/document-types").with(user("alice", "wms-admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"invoice\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_NAME_TAKEN"));
    }

    @Test
    void updatesAndDeletesTypesThatNoDocumentUses() throws Exception {
        String id = create("{\"name\":\"Invoice\"}");
        mvc.perform(put("/document-types/{id}", id).with(user("dave", "document-admin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Supplier invoice\",\"form\":{\"key\":\"invoice-meta\",\"version\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Supplier invoice"));

        jdbc.sql("""
                        INSERT INTO document (id, folder_id, name, type_id, metadata_json, search_text, created_by, created_at, updated_at)
                        VALUES ('d1', '00000000-0000-0000-0000-000000000001', 'a.pdf', :type, '{}', 'a.pdf', 'dave', NOW(6), NOW(6))""")
                .param("type", id).update();
        mvc.perform(delete("/document-types/{id}", id).with(user("dave", "document-admin")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_TYPE_IN_USE"));
        jdbc.sql("DELETE FROM document").update();
        mvc.perform(delete("/document-types/{id}", id).with(user("dave", "document-admin"))).andExpect(status().isNoContent());
    }
}
