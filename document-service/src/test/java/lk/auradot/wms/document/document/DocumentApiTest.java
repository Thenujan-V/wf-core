package lk.auradot.wms.document.document;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeForms;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestDocuments;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import lk.auradot.wms.document.forms.FormRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class DocumentApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeForms forms;

    String finance;
    String hr;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        groups.reset();
        forms.reset();
        finance = folder("Finance", true, "GROUP", "g-fin", "WRITE");
        hr = folder("HR", false, "USER", "erin", "READ");
        snapshots.invalidate();
    }

    String folder(String name, boolean inherit, String type, String principal, String permission) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :root, :name, :inherit, 'dave', NOW(6))""")
                .param("id", id).param("root", Folders.ROOT_ID).param("name", name).param("inherit", inherit).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, :type, :principal, :permission)")
                .param("id", id).param("type", type).param("principal", principal).param("permission", permission).update();
        return id;
    }

    long outboxRows(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE routing_key = :k").param("k", key).query(Long.class).single();
    }

    @Test
    void anUploadedDocumentIsAFile() throws Exception {
        String doc = TestDocuments.create(jdbc, finance, "Invoice.pdf", "bob", "1.0");

        mvc.perform(get("/folders/{id}/documents", finance).with(user("carol")))
                .andExpect(jsonPath("$[0].kind").value("FILE"));
        mvc.perform(get("/documents/{id}", doc).with(user("carol")))
                .andExpect(status().isOk());
    }

    @Test
    void listsAFoldersDocumentsWithTheirCurrentVersion() throws Exception {
        String doc = TestDocuments.create(jdbc, finance, "Invoice March.pdf", "bob", "1.0", "1.1");
        TestDocuments.create(jdbc, finance, "Budget.xlsx", "bob", "1.0");

        mvc.perform(get("/folders/{id}/documents", finance).with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("Budget.xlsx"))
                .andExpect(jsonPath("$[1].currentVersion.label").value("1.1"));
        mvc.perform(get("/documents/{id}", doc).with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permission").value("WRITE"))
                .andExpect(jsonPath("$.versions[*].label").value(org.hamcrest.Matchers.contains("1.1", "1.0")));
    }

    @Test
    void documentsInAFolderTheCallerCannotReadDoNotExistForThem() throws Exception {
        String secret = TestDocuments.create(jdbc, hr, "Salaries.xlsx", "erin", "1.0");

        mvc.perform(get("/documents/{id}", secret).with(user("carol")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_NOT_FOUND"));
        mvc.perform(get("/folders/{id}/documents", hr).with(user("carol"))).andExpect(status().isNotFound());
        mvc.perform(get("/documents/{id}", secret).with(user("erin"))).andExpect(status().isOk());
        mvc.perform(patch("/documents/{id}", secret).with(user("erin")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pay.xlsx\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void renamesAndSetsValidatedMetadata() throws Exception {
        forms.publish(new FormRef("invoice-meta", 1), Set.of("supplier", "amount"), Set.of("supplier"));
        jdbc.sql("INSERT INTO document_type VALUES ('t-inv', 'Invoice', 'invoice-meta', 1, NOW(6))").update();
        String doc = TestDocuments.create(jdbc, finance, "scan.pdf", "bob", "1.0");
        TestDocuments.create(jdbc, finance, "taken.pdf", "bob", "1.0");

        mvc.perform(patch("/documents/{id}", doc).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Taken.pdf\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NAME_TAKEN"));
        mvc.perform(patch("/documents/{id}", doc).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Invoice Acme.pdf\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Invoice Acme.pdf"));
        mvc.perform(put("/documents/{id}/metadata", doc).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeId\":\"t-inv\",\"metadata\":{\"amount\":5}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_METADATA_INVALID"))
                .andExpect(jsonPath("$.errors[0].field").value("supplier"));
        mvc.perform(put("/documents/{id}/metadata", doc).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"typeId\":\"t-inv\",\"metadata\":{\"supplier\":\"Acme\",\"amount\":5}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.typeId").value("t-inv"))
                .andExpect(jsonPath("$.metadata.supplier").value("Acme"));

        String searchText = jdbc.sql("SELECT search_text FROM document WHERE id = :id").param("id", doc).query(String.class).single();
        assertThat(searchText).contains("Invoice Acme.pdf", "Acme", "5");
    }

    @Test
    void oneHolderAtATimeAndOnlyTheHolderOrAManagerReleasesTheLock() throws Exception {
        String doc = TestDocuments.create(jdbc, finance, "Contract.docx", "bob", "1.0");

        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkedOutBy").value("bob"));
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("carol")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_CHECKED_OUT"))
                .andExpect(jsonPath("$.checkedOutBy").value("bob"));
        mvc.perform(patch("/documents/{id}", doc).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Other.docx\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_CHECKED_OUT"));
        mvc.perform(post("/documents/{id}/checkout/cancel", doc).with(user("carol")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_CHECKED_OUT"));

        mvc.perform(post("/documents/{id}/checkout/cancel", doc).with(user("dave", "document-admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.checkedOutBy").doesNotExist());
        assertThat(outboxRows("document.checkout.forced")).isEqualTo(1);

        mvc.perform(post("/documents/{id}/checkout/cancel", doc).with(user("bob")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NOT_CHECKED_OUT"));
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("erin"))).andExpect(status().isForbidden());
    }

    @Test
    void handsOutShortLivedDownloadLinksThatOnlyPreviewSafeTypesInline() throws Exception {
        String doc = TestDocuments.create(jdbc, finance, "Plan.pdf", "bob", "1.0");
        String version = jdbc.sql("SELECT current_version_id FROM document WHERE id = :id").param("id", doc).query(String.class).single();

        mvc.perform(get("/documents/{id}/versions/{v}/content", doc, version).param("inline", "true").with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inline").value(true))
                .andExpect(jsonPath("$.fileName").value("Plan.pdf"))
                .andExpect(jsonPath("$.url", containsString("X-Amz-Signature")))
                .andExpect(jsonPath("$.url", containsString("response-content-disposition=inline")));

        jdbc.sql("UPDATE document_version SET mime = 'text/html' WHERE id = :v").param("v", version).update();
        mvc.perform(get("/documents/{id}/versions/{v}/content", doc, version).param("inline", "true").with(user("carol")))
                .andExpect(jsonPath("$.inline").value(false));
        mvc.perform(get("/documents/{id}/versions/{v}/content", doc, "nope").with(user("carol")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_VERSION_NOT_FOUND"));
    }

    @Test
    void restoringAnOldVersionMakesANewCurrentVersionWithItsFileAndText() throws Exception {
        String doc = TestDocuments.create(jdbc, finance, "Policy.pdf", "bob", "1.0", "1.1");
        String first = jdbc.sql("SELECT id FROM document_version WHERE document_id = :d AND minor = 0")
                .param("d", doc).query(String.class).single();
        jdbc.sql("INSERT INTO document_text VALUES (:v, :d, 'first text', NOW(6))").param("v", first).param("d", doc).update();

        // A restored version is a new version: like an upload, only the holder of the check-out creates it (spec §6).
        mvc.perform(post("/documents/{id}/versions/{v}/restore", doc, first).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NOT_CHECKED_OUT"));
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("carol"))).andExpect(status().isOk());

        mvc.perform(post("/documents/{id}/versions/{v}/restore", doc, first).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedOutBy").doesNotExist())
                .andExpect(jsonPath("$.versions[0].label").value("1.2"))
                .andExpect(jsonPath("$.versions[0].comment").value("Restored from version 1.0"))
                .andExpect(jsonPath("$.currentVersionId").value(org.hamcrest.Matchers.not(first)));

        String restored = jdbc.sql("SELECT current_version_id FROM document WHERE id = :id").param("id", doc).query(String.class).single();
        assertThat(jdbc.sql("SELECT s3_key FROM document_version WHERE id = :v").param("v", restored).query(String.class).single())
                .isEqualTo("objects/" + first);
        assertThat(jdbc.sql("SELECT content FROM document_text WHERE version_id = :v").param("v", restored).query(String.class).single())
                .isEqualTo("first text");
        assertThat(outboxRows("document.version.uploaded")).isEqualTo(1);
    }

    @Test
    void deletingNeedsDeletePermissionAndNoOtherHolder() throws Exception {
        String doc = TestDocuments.create(jdbc, finance, "Old.pdf", "bob", "1.0");

        mvc.perform(delete("/documents/{id}", doc).with(user("carol"))).andExpect(status().isForbidden());
        jdbc.sql("UPDATE document SET checked_out_by = 'bob', checked_out_at = NOW(6) WHERE id = :id").param("id", doc).update();
        mvc.perform(delete("/documents/{id}", doc).with(user("dave", "document-admin")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_CHECKED_OUT"));
        jdbc.sql("UPDATE document SET checked_out_by = NULL, checked_out_at = NULL WHERE id = :id").param("id", doc).update();

        mvc.perform(delete("/documents/{id}", doc).with(user("dave", "document-admin"))).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_version").query(Long.class).single()).isZero();
        assertThat(outboxRows("document.deleted")).isEqualTo(1);
    }
}
