package lk.auradot.wms.document.search;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestDocuments;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import lk.auradot.wms.document.type.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class SearchApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;

    String finance;
    String hr;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        groups.reset();
        finance = folder("Finance", true, "GROUP", "g-fin", "READ");
        hr = folder("HR", false, "USER", "erin", "READ");
        snapshots.invalidate();

        String invoice = TestDocuments.create(jdbc, finance, "Invoice March.pdf", "bob", "1.0");
        jdbc.sql("UPDATE document SET search_text = :t WHERE id = :id")
                .param("t", Metadata.searchText("Invoice March.pdf", Map.of("supplier", "Acme Trading"))).param("id", invoice).update();
        String notes = TestDocuments.create(jdbc, finance, "Notes.txt", "bob", "1.0", "1.1");
        text(notes, 0, "obsolete draft");
        text(notes, 1, "quarterly budget review");
        TestDocuments.create(jdbc, hr, "Budget HR.xlsx", "erin", "1.0");
    }

    String folder(String name, boolean inherit, String type, String principal, String permission) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO folder VALUES (:id, :root, :name, :inherit, 'dave', NOW(6))")
                .param("id", id).param("root", Folders.ROOT_ID).param("name", name).param("inherit", inherit).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, :type, :principal, :permission)")
                .param("id", id).param("type", type).param("principal", principal).param("permission", permission).update();
        return id;
    }

    void text(String documentId, int minor, String content) {
        jdbc.sql("""
                        INSERT INTO document_text (version_id, document_id, content, extracted_at)
                        SELECT id, document_id, :content, NOW(6) FROM document_version WHERE document_id = :d AND minor = :m""")
                .param("content", content).param("d", documentId).param("m", minor).update();
    }

    @Test
    void findsDocumentsByPartOfTheirName() throws Exception {
        mvc.perform(get("/search").param("q", "voice").with(user("carol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].document.name").value("Invoice March.pdf"))
                .andExpect(jsonPath("$.items[0].folderName").value("Finance"));
    }

    @Test
    void findsByMetadataAndByTheCurrentVersionsTextOnly() throws Exception {
        mvc.perform(get("/search").param("q", "acme").with(user("carol")))
                .andExpect(jsonPath("$.items[*].document.name", containsInAnyOrder("Invoice March.pdf")));
        mvc.perform(get("/search").param("q", "budget review").with(user("carol")))
                .andExpect(jsonPath("$.items[*].document.name", containsInAnyOrder("Notes.txt")));
        mvc.perform(get("/search").param("q", "obsolete").with(user("carol")))
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void neverShowsDocumentsFromFoldersTheCallerCannotRead() throws Exception {
        mvc.perform(get("/search").param("q", "budget").with(user("carol")))
                .andExpect(jsonPath("$.items[*].document.name", containsInAnyOrder("Notes.txt")));
        mvc.perform(get("/search").param("q", "budget").with(user("erin")))
                .andExpect(jsonPath("$.items[*].document.name", containsInAnyOrder("Notes.txt", "Budget HR.xlsx")));
        mvc.perform(get("/search").param("q", "budget").with(user("dave", "document-admin")))
                .andExpect(jsonPath("$.total").value(2));
    }

    @Test
    void searchesWithinAFolderTheCallerCanSee() throws Exception {
        mvc.perform(get("/search").param("q", "budget").param("folderId", finance).with(user("erin")))
                .andExpect(jsonPath("$.items[*].document.name", containsInAnyOrder("Notes.txt")));
        mvc.perform(get("/search").param("q", "budget").param("folderId", hr).with(user("carol")))
                .andExpect(status().isNotFound());
    }

    @Test
    void hostileOrEmptyInputIsAnsweredWithoutErrors() throws Exception {
        mvc.perform(get("/search").param("q", "+\"inv*oice (").with(user("carol"))).andExpect(status().isOk());
        mvc.perform(get("/search").param("q", "a").with(user("carol")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_SEARCH_QUERY"));
        mvc.perform(get("/search").param("q", "%%% ---").with(user("carol")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_SEARCH_QUERY"));
        mvc.perform(get("/search").with(user("carol")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_SEARCH_QUERY"));
    }

    @Test
    void pagesThroughResults() throws Exception {
        for (int i = 1; i <= 3; i++) {
            TestDocuments.create(jdbc, finance, "Report " + i + ".pdf", "bob", "1.0");
        }

        mvc.perform(get("/search").param("q", "report").param("size", "2").with(user("carol")))
                .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.items", hasSize(2)));
        mvc.perform(get("/search").param("q", "report").param("size", "2").param("page", "1").with(user("carol")))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mvc.perform(get("/search").param("q", "report").param("size", "500").with(user("carol")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_SEARCH_PAGE"));
    }

    @Test
    void findsDocumentsNamedInSinhalaOrTamil() throws Exception {
        TestDocuments.create(jdbc, finance, "ලංකාව වාර්තාව.pdf", "bob", "1.0");
        TestDocuments.create(jdbc, finance, "சென்னை அறிக்கை.pdf", "bob", "1.0");

        mvc.perform(get("/search").param("q", "වාර්තාව").with(user("carol")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].document.name", containsInAnyOrder("ලංකාව වාර්තාව.pdf")));
        mvc.perform(get("/search").param("q", "சென்னை").with(user("carol")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].document.name", containsInAnyOrder("சென்னை அறிக்கை.pdf")));
    }
}
