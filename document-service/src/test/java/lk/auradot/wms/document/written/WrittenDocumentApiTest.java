package lk.auradot.wms.document.written;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestDocuments;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class WrittenDocumentApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired JsonMapper json;

    String finance;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        groups.reset();
        finance = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :root, 'Finance', TRUE, 'dave', NOW(6))""")
                .param("id", finance).param("root", Folders.ROOT_ID).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, 'GROUP', 'g-fin', 'WRITE')").param("id", finance).update();
        snapshots.invalidate();
    }

    String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    /** bob creates "Travel policy" in Finance; the answer's id. */
    String create() throws Exception {
        String answer = mvc.perform(post("/written").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("folderId", finance, "name", "Travel policy"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(answer).get("id").asString();
    }

    void save(String id, String who, String html, int revision, int expectedStatus) throws Exception {
        mvc.perform(put("/documents/{id}/draft", id).with(user(who)).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", html, "revision", revision))))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void createsAWrittenDocumentWithAnEmptyDraft() throws Exception {
        String id = create();

        mvc.perform(get("/documents/{id}", id).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Travel policy"))
                .andExpect(jsonPath("$.kind").value("WRITTEN"))
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.hasDraft").value(true))
                .andExpect(jsonPath("$.currentVersionId").doesNotExist())
                .andExpect(jsonPath("$.versions").isEmpty());
        mvc.perform(get("/documents/{id}/draft", id).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value(""))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.editable").value(false)); // not checked out yet
    }

    @Test
    void creatingNeedsWriteOnTheFolderAndAFreeName() throws Exception {
        mvc.perform(post("/written").with(user("erin")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("folderId", finance, "name", "Mine"))))
                .andExpect(status().isForbidden()); // erin only reads, through the root
        create();
        mvc.perform(post("/written").with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("folderId", finance, "name", "Travel policy"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NAME_TAKEN"));
        mvc.perform(post("/written").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("folderId", Folders.PROCESSES_ID, "name", "Sneaky"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_SYSTEM_FOLDER"));
        mvc.perform(post("/written").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("folderId", finance, "name", "a/b"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_NAME_INVALID"));
    }

    @Test
    void theHolderOfTheCheckOutSavesAndGetsBackWhatWasKept() throws Exception {
        String id = create();
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());

        mvc.perform(put("/documents/{id}/draft", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", "<p onclick=\"x()\">Hello</p><script>x()</script>", "revision", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>Hello</p>"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.updatedBy").value("bob"))
                .andExpect(jsonPath("$.editable").value(true));
        assertThat(jdbc.sql("SELECT content_html FROM document_draft WHERE document_id = :id").param("id", id)
                .query(String.class).single()).isEqualTo("<p>Hello</p>");
    }

    @Test
    void aSaveAgainstAnOlderTextIsRefusedAndChangesNothing() throws Exception {
        String id = create();
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());
        save(id, "bob", "<p>First</p>", 0, 200);

        mvc.perform(put("/documents/{id}/draft", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", "<p>From the other tab</p>", "revision", 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_DRAFT_CHANGED"));
        mvc.perform(get("/documents/{id}/draft", id).with(user("bob")))
                .andExpect(jsonPath("$.html").value("<p>First</p>")).andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void sendingTheSameSaveAgainAfterALostAnswerIsNotAConflict() throws Exception {
        String id = create();
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());
        save(id, "bob", "<p>First</p>", 0, 200);

        // The browser never heard back, so it sends the same text against the same revision.
        mvc.perform(put("/documents/{id}/draft", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", "<p>First</p>", "revision", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.html").value("<p>First</p>"))
                .andExpect(jsonPath("$.revision").value(1));
        // Only the very last save is recognised: an older revision is a conflict even with the same words.
        save(id, "bob", "<p>Second</p>", 1, 200);
        save(id, "bob", "<p>First</p>", 0, 409);
    }

    @Test
    void savingNeedsTheCheckOut() throws Exception {
        String id = create();

        mvc.perform(put("/documents/{id}/draft", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", "<p>x</p>", "revision", 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NOT_CHECKED_OUT"));
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(put("/documents/{id}/draft", id).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", "<p>x</p>", "revision", 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_CHECKED_OUT"));
        // carol may read the draft, but not edit it while bob holds it
        mvc.perform(get("/documents/{id}/draft", id).with(user("carol")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void nobodySavesWhileTheDocumentIsInApproval() throws Exception {
        String id = create();
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());
        jdbc.sql("UPDATE document SET linked_instance_id = 'i1' WHERE id = :id").param("id", id).update();

        mvc.perform(put("/documents/{id}/draft", id).with(user("bob", "document-admin")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", "<p>x</p>", "revision", 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_IN_APPROVAL"));
    }

    @Test
    void aTextThatIsTooLongIsRefused() throws Exception {
        String id = create();
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());

        mvc.perform(put("/documents/{id}/draft", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("html", "<p>" + "x".repeat(HtmlCleaner.MAX_BYTES) + "</p>", "revision", 0))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_TEXT_TOO_LARGE"));
    }

    @Test
    void anUploadedFileHasNoDraft() throws Exception {
        String file = TestDocuments.create(jdbc, finance, "Invoice.pdf", "bob", "1.0");

        mvc.perform(get("/documents/{id}/draft", file).with(user("bob")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NOT_WRITTEN"));
        mvc.perform(get("/documents/{id}", file).with(user("bob")))
                .andExpect(jsonPath("$.kind").value("FILE"))
                .andExpect(jsonPath("$.state").doesNotExist())
                .andExpect(jsonPath("$.hasDraft").value(false));
    }
}
