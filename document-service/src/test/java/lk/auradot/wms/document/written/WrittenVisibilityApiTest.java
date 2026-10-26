package lk.auradot.wms.document.written;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class WrittenVisibilityApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;

    String finance;
    String draft;

    @BeforeEach
    void reset() throws Exception {
        TestDb.clear(jdbc);
        groups.reset();
        finance = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :root, 'Finance', TRUE, 'dave', NOW(6))""")
                .param("id", finance).param("root", Folders.ROOT_ID).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, 'GROUP', 'g-fin', 'WRITE')").param("id", finance).update();
        snapshots.invalidate();
        draft = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO document (id, folder_id, name, metadata_json, search_text, created_by, created_at, updated_at, kind)
                        VALUES (:id, :folder, 'Travel policy', '{}', 'Travel policy', 'bob', NOW(6), NOW(6), 'WRITTEN')""")
                .param("id", draft).param("folder", finance).update();
        jdbc.sql("INSERT INTO document_draft VALUES (:id, '<p>Secret plan</p>', 1, 'bob', NOW(6))").param("id", draft).update();
    }

    /** Gives the document an approved version 1.0, as approval will in a later phase. */
    void approve() {
        String version = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO document_version (id, document_id, major, minor, s3_key, size, mime, sha256, file_name,
                                                      created_by, created_at)
                        VALUES (:v, :d, 1, 0, :key, 10, 'application/pdf', :sha, 'Travel policy.pdf', 'bob', NOW(6))""")
                .param("v", version).param("d", draft).param("key", "objects/" + version).param("sha", "a".repeat(64)).update();
        jdbc.sql("UPDATE document SET current_version_id = :v WHERE id = :d").param("v", version).param("d", draft).update();
        jdbc.sql("DELETE FROM document_draft WHERE document_id = :d").param("d", draft).update();
    }

    @Test
    void aDraftDoesNotExistForSomeoneWhoCanOnlyRead() throws Exception {
        // erin reads Finance through the root's "every user reads"; bob and carol write through g-fin
        mvc.perform(get("/documents/{id}", draft).with(user("erin"))).andExpect(status().isNotFound());
        mvc.perform(get("/documents/{id}/draft", draft).with(user("erin"))).andExpect(status().isNotFound());
        mvc.perform(get("/folders/{id}/documents", finance).with(user("erin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(get("/documents/{id}", draft).with(user("carol")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DRAFT"));
        mvc.perform(get("/folders/{id}/documents", finance).with(user("carol")))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].kind").value("WRITTEN"));
    }

    @Test
    void onceApprovedEveryReaderSeesIt() throws Exception {
        approve();

        mvc.perform(get("/documents/{id}", draft).with(user("erin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.permission").value("READ"));
        mvc.perform(get("/folders/{id}/documents", finance).with(user("erin"))).andExpect(jsonPath("$", hasSize(1)));
        // but the text being worked on stays with the people who may change it
        mvc.perform(get("/documents/{id}/draft", draft).with(user("erin"))).andExpect(status().isForbidden());
    }

    @Test
    void searchLeavesOutWhatWasNeverApproved() throws Exception {
        mvc.perform(get("/search").param("q", "Travel").with(user("carol")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        approve();
        mvc.perform(get("/search").param("q", "Travel").with(user("erin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void aWrittenDocumentTakesNoUploadedOrRestoredVersion() throws Exception {
        approve();
        mvc.perform(post("/documents/{id}/checkout", draft).with(user("bob"))).andExpect(status().isOk());
        String version = jdbc.sql("SELECT current_version_id FROM document WHERE id = :d").param("d", draft)
                .query(String.class).single();

        mvc.perform(post("/uploads").with(user("bob")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"documentId": "%s", "fileName": "x.pdf", "size": 10, "sha256": "%s", "mimeType": "application/pdf"}"""
                        .formatted(draft, "a".repeat(64))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_WRITTEN"));
        mvc.perform(post("/documents/{id}/versions/{v}/restore", draft, version).with(user("bob")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_WRITTEN"));
    }
}
