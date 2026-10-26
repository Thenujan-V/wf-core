package lk.auradot.wms.document.written;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.FakeOutcomes;
import lk.auradot.wms.document.FakePeople;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestFiles;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.approval.RequestOutcome;
import lk.auradot.wms.document.folder.Folders;
import org.apache.tika.Tika;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An approved request turns the reviewed text into the document's next version, with its approval record. */
@DocumentIntegrationTest
class ApprovalSettlementTest {

    @Autowired ApprovalOutcomeListener listener;
    @Autowired ApprovalSettlement settlement;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;
    @Autowired FakeOutcomes outcomes;
    @Autowired FakePeople people;
    @Autowired JsonMapper json;

    String doc;
    String finance;
    @Autowired lk.auradot.wms.document.MutableClock clock;

    @BeforeEach
    void reset() throws Exception {
        TestDb.clear(jdbc);
        groups.reset();
        instances.reset();
        outcomes.reset();
        people.reset();
        finance = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :root, 'Finance', TRUE, 'dave', NOW(6))""")
                .param("id", finance).param("root", Folders.ROOT_ID).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, 'GROUP', 'g-fin', 'WRITE')").param("id", finance).update();
        snapshots.invalidate();
        instances.grant("bob", "i1", "document-approval", true);

        String answer = mvc.perform(post("/written").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("folderId", finance, "name", "Travel policy"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        doc = json.readTree(answer).get("id").asString();
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(put("/documents/{id}/draft", doc).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("html", "<p>Fly economy.</p>", "revision", 0))))
                .andExpect(status().isOk());
        mvc.perform(post("/documents/{id}/link", doc).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("instanceId", "i1"))))
                .andExpect(status().isOk());
    }

    RequestOutcome ended(String endStatus) {
        return new RequestOutcome("i1", "document-approval", "Document approval", "DOC-42",
                endStatus == null ? "CANCELLED" : "COMPLETED", endStatus, Instant.parse("2026-10-06T09:30:00Z"),
                List.of(new RequestOutcome.Step("Manager approval", "alice", "APPROVE", Instant.parse("2026-10-06T09:30:00Z"))));
    }

    void deliver(String messageId, String endStatus) throws Exception {
        Map<String, Object> event = Map.of("messageId", messageId, "type", "workflow.instance.completed", "instanceId", "i1",
                "data", Map.of("endStatus", endStatus));
        listener.onRequestEnded(MessageBuilder.withBody(json.writeValueAsBytes(event)).build());
    }

    Map<String, Object> round() {
        return jdbc.sql("SELECT status, end_status, version_id FROM document_submission WHERE instance_id = 'i1'").query().singleRow();
    }

    long events(String key) {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE routing_key = :k").param("k", key).query(Long.class).single();
    }

    long versions() {
        return jdbc.sql("SELECT COUNT(*) FROM document_version WHERE document_id = :d").param("d", doc).query(Long.class).single();
    }

    @Test
    void anApprovedRequestMakesVersionOnePointNoughtOfTheReviewedTextWithItsRecord() throws Exception {
        outcomes.put(ended("APPROVED"));

        deliver("m1", "APPROVED");

        String view = mvc.perform(get("/documents/{id}", doc).with(user("bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("APPROVED"))
                .andExpect(jsonPath("$.hasDraft").value(false))
                .andExpect(jsonPath("$.linkedInstanceId").doesNotExist())
                .andExpect(jsonPath("$.checkedOutBy").doesNotExist())
                .andExpect(jsonPath("$.versions", hasSize(1)))
                .andExpect(jsonPath("$.versions[0].label").value("1.0"))
                .andExpect(jsonPath("$.versions[0].mimeType").value("application/pdf"))
                .andExpect(jsonPath("$.versions[0].fileName").value("Travel policy.pdf"))
                .andExpect(jsonPath("$.versions[0].createdBy").value("bob"))
                .andReturn().getResponse().getContentAsString();
        String versionId = json.readTree(view).get("currentVersionId").asString();
        assertThat(round()).containsEntry("status", "APPROVED").containsEntry("end_status", "APPROVED")
                .containsEntry("version_id", versionId);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_draft WHERE document_id = :d").param("d", doc).query(Long.class).single()).isZero();
        assertThat(events("document.approved")).isEqualTo(1);
        assertThat(events("document.version.uploaded")).isEqualTo(1);
        assertThat(events("document.returned")).isZero();

        String link = mvc.perform(get("/documents/{id}/versions/{v}/content", doc, versionId).param("inline", "true").with(user("bob")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        byte[] pdf = TestFiles.get(URI.create(json.readTree(link).get("url").asString())).body();
        assertThat(jdbc.sql("SELECT sha256 FROM document_version WHERE id = :v").param("v", versionId).query(String.class).single())
                .isEqualTo(TestFiles.sha256(pdf));
        String text = new Tika().parseToString(new ByteArrayInputStream(pdf));
        assertThat(text).contains("Fly economy.").contains("Travel policy · version 1.0").doesNotContain("For review");
        assertThat(text).contains("Approval record").contains("DOC-42").contains("Document approval")
                .contains("Name of bob").contains("Manager approval").contains("Name of alice").contains("APPROVE");
    }

    @Test
    void onceApprovedEveryReaderOfTheFolderSeesThePdfAndNobodyEditsIt() throws Exception {
        outcomes.put(ended("APPROVED"));
        deliver("m1", "APPROVED");

        // erin only reads Finance (through the root); until now the document did not exist for her
        mvc.perform(get("/documents/{id}", doc).with(user("erin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("APPROVED")).andExpect(jsonPath("$.permission").value("READ"));
        mvc.perform(get("/documents/{id}/draft", doc).with(user("bob")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_NO_DRAFT"));
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(put("/documents/{id}/draft", doc).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("html", "<p>Changed</p>", "revision", 1))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_NO_DRAFT"));
    }

    @Test
    void whenWorkflowOrIdentityCannotAnswerTheDocumentWaitsInApprovalAndIsFinishedLater() throws Exception {
        outcomes.put(ended("APPROVED"));
        outcomes.fail();
        deliver("m1", "APPROVED");

        assertThat(round()).containsEntry("status", "ENDED");
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.state").value("IN_APPROVAL"));
        mvc.perform(get("/documents/{id}/review", doc).with(user("bob"))).andExpect(status().isOk()); // still readable
        assertThat(versions()).isZero();

        outcomes.recover();
        people.fail();
        assertThat(settlement.settleEnded()).isZero();
        assertThat(round()).containsEntry("status", "ENDED");
        assertThat(versions()).isZero();

        people.recover();
        assertThat(settlement.settleEnded()).isEqualTo(1);
        assertThat(round()).containsEntry("status", "APPROVED");
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.state").value("APPROVED"));
        assertThat(versions()).isEqualTo(1);
    }

    @Test
    void settlingTwiceMakesOneVersionAndOneAnnouncement() throws Exception {
        outcomes.put(ended("APPROVED"));
        deliver("m1", "APPROVED");
        String roundId = jdbc.sql("SELECT id FROM document_submission WHERE instance_id = 'i1'").query(String.class).single();

        deliver("m1", "APPROVED");           // the same message again
        deliver("m2", "APPROVED");           // the same news under another id
        settlement.settle(roundId);          // the schedule arriving late
        assertThat(settlement.settleEnded()).isZero();

        assertThat(versions()).isEqualTo(1);
        assertThat(events("document.approved")).isEqualTo(1);
        assertThat(events("document.version.uploaded")).isEqualTo(1);
    }

    @Test
    void theMessageSaysApprovedButWorkflowSaysOtherwise() throws Exception {
        outcomes.put(ended("REJECTED"));
        deliver("m1", "APPROVED");
        assertThat(round()).containsEntry("status", "NOT_APPROVED").containsEntry("end_status", "REJECTED");
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.state").value("DRAFT"));
        assertThat(versions()).isZero();
        assertThat(events("document.returned")).isEqualTo(1);
    }

    @Test
    void aRequestWorkflowNoLongerKnowsIsNotAnApproval() throws Exception {
        deliver("m1", "APPROVED"); // nothing put into the fake: workflow-service answers "no such request"

        assertThat(round()).containsEntry("status", "NOT_APPROVED");
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.state").value("DRAFT"));
    }

    @Test
    void theApprovedPdfIsTheTextThatWasReviewedNotWhatTheDraftSaysNow() throws Exception {
        // Nothing can save while in approval, so the draft is changed behind the service's back.
        jdbc.sql("UPDATE document_draft SET content_html = '<p>Sneaked in later</p>' WHERE document_id = :d").param("d", doc).update();
        outcomes.put(ended("APPROVED"));

        deliver("m1", "APPROVED");

        String view = mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andReturn().getResponse().getContentAsString();
        String versionId = json.readTree(view).get("currentVersionId").asString();
        String link = mvc.perform(get("/documents/{id}/versions/{v}/content", doc, versionId).with(user("bob")))
                .andReturn().getResponse().getContentAsString();
        String text = new Tika().parseToString(new ByteArrayInputStream(
                TestFiles.get(URI.create(json.readTree(link).get("url").asString())).body()));
        assertThat(text).contains("Fly economy.").doesNotContain("Sneaked in later");
    }

    /** A second written document of bob's, sent to another request. */
    void sendAnother(String name, String instanceId) throws Exception {
        instances.grant("bob", instanceId, "document-approval", true);
        String answer = mvc.perform(post("/written").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("folderId", finance, "name", name))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(answer).get("id").asString();
        mvc.perform(post("/documents/{id}/checkout", id).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(put("/documents/{id}/draft", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("html", "<p>Other text.</p>", "revision", 0))))
                .andExpect(status().isOk());
        mvc.perform(post("/documents/{id}/link", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("instanceId", instanceId))))
                .andExpect(status().isOk());
    }

    /**
     * Every question to a service that does not answer costs a timeout, on the thread the other schedules share. One
     * unanswered question says the service is down; the rest of the pass would only wait for the same silence.
     */
    @Test
    void aServiceThatIsDownIsAskedOncePerPassNotOncePerWaitingRound() throws Exception {
        sendAnother("Second policy", "i2");
        sendAnother("Third policy", "i3");
        outcomes.fail();
        clock.advance(java.time.Duration.ofMinutes(11)); // all three rounds are old enough to be looked at

        int before = outcomes.asked();
        assertThat(settlement.sweepStale()).isZero();
        assertThat(outcomes.asked() - before).isEqualTo(1);

        jdbc.sql("UPDATE document_submission SET status = 'ENDED'").update();
        before = outcomes.asked();
        assertThat(settlement.settleEnded()).isZero();
        assertThat(outcomes.asked() - before).isEqualTo(1);
    }

    @Test
    void twoSettlementsAtTheSameMomentMakeOneVersionAndNeitherFails() throws Exception {
        outcomes.put(ended("APPROVED"));
        jdbc.sql("UPDATE document_submission SET status = 'ENDED' WHERE instance_id = 'i1'").update();
        String roundId = jdbc.sql("SELECT id FROM document_submission WHERE instance_id = 'i1'").query(String.class).single();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Void> one = () -> {
            go.await();
            settlement.settle(roundId);
            return null;
        };
        try {
            var first = pool.submit(one);
            var second = pool.submit(one);
            go.countDown();
            first.get(60, java.util.concurrent.TimeUnit.SECONDS);   // an exception in either would surface here
            second.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(versions()).isEqualTo(1);
        assertThat(events("document.approved")).isEqualTo(1);
        assertThat(events("document.version.uploaded")).isEqualTo(1);
        // The PDF the version points at is the winner's, and the loser deleted only its own.
        String view = mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andReturn().getResponse().getContentAsString();
        String versionId = json.readTree(view).get("currentVersionId").asString();
        String link = mvc.perform(get("/documents/{id}/versions/{v}/content", doc, versionId).with(user("bob")))
                .andReturn().getResponse().getContentAsString();
        assertThat(TestFiles.get(URI.create(json.readTree(link).get("url").asString())).statusCode()).isEqualTo(200);
    }
}
