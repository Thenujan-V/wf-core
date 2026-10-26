package lk.auradot.wms.document.written;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestFiles;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
@org.springframework.context.annotation.Import(SendForApprovalApiTest.SlowRenderer.class)
class SendForApprovalApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;
    @Autowired lk.auradot.wms.document.FakeOutcomes outcomes;
    @Autowired JsonMapper json;

    String finance;
    String doc;

    @BeforeEach
    void reset() throws Exception {
        TestDb.clear(jdbc);
        groups.reset();
        instances.reset();
        outcomes.reset();
        finance = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :root, 'Finance', TRUE, 'dave', NOW(6))""")
                .param("id", finance).param("root", Folders.ROOT_ID).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, 'GROUP', 'g-fin', 'WRITE')").param("id", finance).update();
        snapshots.invalidate();
        // bob may attach to request i1 (he started it); erin only sees it (she approves); frank is nobody to it.
        instances.grant("bob", "i1", "document-approval", true);
        instances.grant("bob", "i2", "document-approval", true);
        instances.grant("erin", "i1", "document-approval", false);
        doc = written("Travel policy", "<h1>Policy</h1><p>Fly economy.</p>");
    }

    /** bob creates a written document in Finance, writes the text and gives the check-out back. */
    String written(String name, String html) throws Exception {
        String answer = mvc.perform(post("/written").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("folderId", finance, "name", name))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(answer).get("id").asString();
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(put("/documents/{id}/draft", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("html", html, "revision", 0))))
                .andExpect(status().isOk());
        mvc.perform(post("/documents/{id}/checkout/cancel", id).with(user("bob"))).andExpect(status().isOk());
        return id;
    }

    ResultActions send(String id, String who, String instanceId) throws Exception {
        return mvc.perform(post("/documents/{id}/link", id).with(user(who)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("instanceId", instanceId))));
    }

    long rounds(String id) {
        return jdbc.sql("SELECT COUNT(*) FROM document_submission WHERE document_id = :d").param("d", id).query(Long.class).single();
    }

    @Test
    void sendingFreezesTheTextAndMakesAReviewPdfOfIt() throws Exception {
        send(doc, "bob", "i1").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IN_APPROVAL"))
                .andExpect(jsonPath("$.linkedInstanceId").value("i1"))
                .andExpect(jsonPath("$.checkedOutBy").doesNotExist());

        Map<String, Object> round = jdbc.sql("SELECT * FROM document_submission WHERE document_id = :d").param("d", doc)
                .query().singleRow();
        assertThat(round.get("status")).isEqualTo("PENDING");
        assertThat(round.get("instance_id")).isEqualTo("i1");
        assertThat(round.get("content_html")).isEqualTo("<h1>Policy</h1><p>Fly economy.</p>");
        assertThat(round.get("submitted_by")).isEqualTo("bob");
        assertThat((String) round.get("review_s3_key")).startsWith("reviews/").endsWith(".pdf");

        String link = mvc.perform(get("/documents/{id}/review", doc).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.inline").value(true))
                .andReturn().getResponse().getContentAsString();
        byte[] pdf = TestFiles.get(URI.create(json.readTree(link).get("url").asString())).body();
        assertThat(((Number) round.get("review_size")).longValue()).isEqualTo(pdf.length);
        assertThat(round.get("review_sha256")).isEqualTo(TestFiles.sha256(pdf));
        String text = new Tika().parseToString(new ByteArrayInputStream(pdf));
        assertThat(text).contains("Fly economy.").contains("For review — not approved");
    }

    @Test
    void nothingChangesTheDocumentWhileItIsInApproval() throws Exception {
        send(doc, "bob", "i1").andExpect(status().isOk());

        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_IN_APPROVAL"));
        mvc.perform(put("/documents/{id}/draft", doc).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("html", "<p>Changed</p>", "revision", 1))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_IN_APPROVAL"));
        mvc.perform(delete("/documents/{id}", doc).with(user("dave", "document-admin")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_IN_APPROVAL"));
        mvc.perform(get("/documents/{id}/draft", doc).with(user("bob")))
                .andExpect(jsonPath("$.html").value("<h1>Policy</h1><p>Fly economy.</p>"))
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void theSameRequestIsAcceptedOnceAndAnotherRequestIsRefused() throws Exception {
        send(doc, "bob", "i1").andExpect(status().isOk());
        send(doc, "bob", "i1").andExpect(status().isOk()).andExpect(jsonPath("$.state").value("IN_APPROVAL"));
        assertThat(rounds(doc)).isEqualTo(1);

        send(doc, "bob", "i2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_IN_APPROVAL"));
        assertThat(rounds(doc)).isEqualTo(1);
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.linkedInstanceId").value("i1"));
    }

    @Test
    void anEmptyTextOrSomeoneElsesCheckOutStopsTheSend() throws Exception {
        String empty = written("Empty", "<p></p>");
        send(empty, "bob", "i1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_EMPTY"));
        assertThat(rounds(empty)).isZero();

        mvc.perform(post("/documents/{id}/checkout", doc).with(user("carol"))).andExpect(status().isOk());
        send(doc, "bob", "i1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_CHECKED_OUT"));
        assertThat(rounds(doc)).isZero();
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.state").value("DRAFT"));
    }

    @Test
    void sendingNeedsWriteOnTheDocumentAndTheRightToAttachToTheRequest() throws Exception {
        // carol writes in Finance but has nothing to do with request i1
        send(doc, "carol", "i1").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_INSTANCE_NOT_FOUND"));
        // erin sees the request but only reads the folder, so the draft does not exist for her
        send(doc, "erin", "i1").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_NOT_FOUND"));
        assertThat(rounds(doc)).isZero();
    }

    @Test
    void peopleWhoSeeTheRequestReadTheReviewAndNobodyElseDoes() throws Exception {
        send(doc, "bob", "i1").andExpect(status().isOk());

        // erin only reads Finance, but she is part of the request
        mvc.perform(get("/documents/{id}", doc).with(user("erin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IN_APPROVAL"))
                .andExpect(jsonPath("$.permission").value("READ"));
        mvc.perform(get("/documents/{id}/review", doc).with(user("erin"))).andExpect(status().isOk());
        mvc.perform(get("/documents/{id}/draft", doc).with(user("erin"))).andExpect(status().isForbidden());
        mvc.perform(get("/instances/{id}/documents", "i1").with(user("erin")))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].kind").value("WRITTEN"));

        // frank reads Finance like every user, and is no part of the request
        mvc.perform(get("/documents/{id}", doc).with(user("frank"))).andExpect(status().isNotFound());
        mvc.perform(get("/documents/{id}/review", doc).with(user("frank"))).andExpect(status().isNotFound());
    }

    @Test
    void thereIsNoReviewOfADocumentThatIsNotInApproval() throws Exception {
        mvc.perform(get("/documents/{id}/review", doc).with(user("bob")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_NO_REVIEW"));
    }

    @Test
    void deletingTheDocumentLaterAlsoRemovesItsReviewPdf() throws Exception {
        send(doc, "bob", "i1").andExpect(status().isOk());
        String link = mvc.perform(get("/documents/{id}/review", doc).with(user("bob"))).andReturn().getResponse().getContentAsString();
        URI url = URI.create(json.readTree(link).get("url").asString());
        assertThat(TestFiles.get(url).statusCode()).isEqualTo(200);
        // the request ends (Task 3 does this through the listener; here the link is simply cleared)
        jdbc.sql("UPDATE document SET linked_instance_id = NULL WHERE id = :d").param("d", doc).update();

        mvc.perform(delete("/documents/{id}", doc).with(user("dave", "document-admin"))).andExpect(status().isNoContent());

        assertThat(TestFiles.get(url).statusCode()).isEqualTo(404);
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class SlowRenderer {
        /** Runs once in the middle of the next render, where a real save could land. */
        static volatile Runnable duringRender;

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        PdfRenderer interruptibleRenderer() {
            return new PdfRenderer() {
                @Override
                public byte[] render(String title, String bodyHtml, String footerNote) {
                    byte[] pdf = super.render(title, bodyHtml, footerNote);
                    Runnable now = duringRender;
                    duringRender = null;
                    if (now != null) {
                        now.run();
                    }
                    return pdf;
                }
            };
        }
    }

    @Test
    void aSaveThatLandsWhileTheReviewIsBeingMadeStopsTheSend() throws Exception {
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isOk());
        SlowRenderer.duringRender = () -> jdbc.sql(
                "UPDATE document_draft SET content_html = '<p>Changed meanwhile</p>', revision = revision + 1 WHERE document_id = :d")
                .param("d", doc).update();

        send(doc, "bob", "i1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_DRAFT_CHANGED"));

        assertThat(rounds(doc)).isZero();
        mvc.perform(get("/documents/{id}", doc).with(user("bob")))
                .andExpect(jsonPath("$.state").value("DRAFT")).andExpect(jsonPath("$.checkedOutBy").value("bob"));
    }

    /**
     * The request can end between the moment sending checks it and the moment the document is locked: the "request
     * ended" message then finds nothing to unlock, and nothing else ever would.
     */
    @Test
    void aRequestThatEndsWhileTheDocumentIsBeingSentDoesNotKeepItLocked() throws Exception {
        SlowRenderer.duringRender = () -> instances.end("bob", "i1");

        send(doc, "bob", "i1").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.linkedInstanceId").doesNotExist());

        assertThat(jdbc.sql("SELECT status FROM document_submission WHERE document_id = :d").param("d", doc)
                .query(String.class).single()).isEqualTo("NOT_APPROVED");
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isOk());
    }

    @Test
    void aFolderManagerCanTakeADocumentOutOfApprovalWhenItsRequestNeverReportedBack() throws Exception {
        send(doc, "bob", "i1").andExpect(status().isOk());

        // bob writes in Finance but does not manage it
        mvc.perform(post("/documents/{id}/approval/release", doc).with(user("bob"))).andExpect(status().isForbidden());
        mvc.perform(post("/documents/{id}/approval/release", doc).with(user("dave", "document-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DRAFT"))
                .andExpect(jsonPath("$.linkedInstanceId").doesNotExist());

        assertThat(jdbc.sql("SELECT status FROM document_submission WHERE document_id = :d").param("d", doc)
                .query(String.class).single()).isEqualTo("NOT_APPROVED");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE routing_key = 'document.approval.released'")
                .query(Long.class).single()).isEqualTo(1);
        mvc.perform(get("/documents/{id}/draft", doc).with(user("bob")))
                .andExpect(jsonPath("$.html").value("<h1>Policy</h1><p>Fly economy.</p>"));
        mvc.perform(post("/documents/{id}/approval/release", doc).with(user("dave", "document-admin")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NOT_IN_APPROVAL"));
    }

    @Test
    void aRequestThatEndsApprovedWhileTheDocumentIsBeingSentStillGetsItsPdf() throws Exception {
        outcomes.put(new lk.auradot.wms.document.approval.RequestOutcome("i1", "document-approval", "Document approval", null,
                "COMPLETED", "APPROVED", java.time.Instant.parse("2026-10-06T09:30:00Z"), java.util.List.of()));
        SlowRenderer.duringRender = () -> instances.end("bob", "i1");

        send(doc, "bob", "i1").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.versions[0].label").value("1.0"));
    }

    @Test
    void aRequestTakesOneWrittenDocument() throws Exception {
        String other = written("Second policy", "<p>Other text</p>");
        send(doc, "bob", "i1").andExpect(status().isOk());

        send(other, "bob", "i1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_REQUEST_TAKEN"));
        mvc.perform(get("/documents/{id}", other).with(user("bob"))).andExpect(jsonPath("$.state").value("DRAFT"));
    }
}
