package lk.auradot.wms.document.written;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** workflow.instance.completed / cancelled: the round of approval is over and the document unlocks. */
@DocumentIntegrationTest
class ApprovalOutcomeListenerTest {

    @Autowired ApprovalOutcomeListener listener;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;
    @Autowired lk.auradot.wms.document.FakeOutcomes outcomes;
    @Autowired JsonMapper json;

    String doc;

    @BeforeEach
    void reset() throws Exception {
        TestDb.clear(jdbc);
        groups.reset();
        instances.reset();
        outcomes.reset();
        String finance = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :root, 'Finance', TRUE, 'dave', NOW(6))""")
                .param("id", finance).param("root", Folders.ROOT_ID).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, 'GROUP', 'g-fin', 'WRITE')").param("id", finance).update();
        snapshots.invalidate();
        instances.grant("bob", "i1", "document-approval", true);
        instances.grant("bob", "i2", "document-approval", true);
        instances.grant("erin", "i1", "document-approval", false);

        String answer = mvc.perform(post("/written").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("folderId", finance, "name", "Travel policy"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        doc = json.readTree(answer).get("id").asString();
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(put("/documents/{id}/draft", doc).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("html", "<p>Fly economy.</p>", "revision", 0))))
                .andExpect(status().isOk());
        send("i1");
    }

    void send(String instanceId) throws Exception {
        mvc.perform(post("/documents/{id}/link", doc).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("instanceId", instanceId))))
                .andExpect(status().isOk());
    }

    /** As workflow-service publishes it (phase 1 plan, Finding C): the end status is inside "data". */
    void deliver(String messageId, String type, String instanceId, Map<String, Object> data) throws Exception {
        Map<String, Object> event = new HashMap<>(Map.of("messageId", messageId, "type", type, "instanceId", instanceId,
                "processKey", "document-approval", "occurredAt", "2026-10-06T10:00:00Z"));
        if (data != null) {
            event.put("data", data);
        }
        listener.onRequestEnded(MessageBuilder.withBody(json.writeValueAsBytes(event)).build());
    }

    Map<String, Object> round(String instanceId) {
        return jdbc.sql("SELECT status, end_status, decided_at FROM document_submission WHERE instance_id = :i")
                .param("i", instanceId).query().singleRow();
    }

    long returnedEvents() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE routing_key = 'document.returned'").query(Long.class).single();
    }

    @Test
    void aRejectedRequestGivesTheDocumentBackAsADraftWithItsText() throws Exception {
        deliver("m1", "workflow.instance.completed", "i1", Map.of("endStatus", "REJECTED"));

        assertThat(round("i1")).containsEntry("status", "NOT_APPROVED").containsEntry("end_status", "REJECTED");
        assertThat(round("i1").get("decided_at")).isNotNull();
        mvc.perform(get("/documents/{id}", doc).with(user("bob")))
                .andExpect(jsonPath("$.state").value("DRAFT")).andExpect(jsonPath("$.linkedInstanceId").doesNotExist());
        mvc.perform(get("/documents/{id}/draft", doc).with(user("bob"))).andExpect(jsonPath("$.html").value("<p>Fly economy.</p>"));
        assertThat(returnedEvents()).isEqualTo(1);

        // and it can be edited and sent again, to a new request
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isOk());
        mvc.perform(put("/documents/{id}/draft", doc).with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("html", "<p>Fly economy, book early.</p>", "revision", 1))))
                .andExpect(status().isOk());
        send("i2");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document_submission WHERE document_id = :d").param("d", doc)
                .query(Long.class).single()).isEqualTo(2);
    }

    @Test
    void aCancelledRequestIsNotApprovedAndHasNoEndStatus() throws Exception {
        deliver("m1", "workflow.instance.cancelled", "i1", Map.of("actorId", "alice"));

        assertThat(round("i1")).containsEntry("status", "NOT_APPROVED").containsEntry("end_status", null);
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.state").value("DRAFT"));
        assertThat(returnedEvents()).isEqualTo(1);
    }

    @Test
    void anApprovedRequestIsLeftToTheSettlementWhichMakesTheVersion() throws Exception {
        outcomes.put(new lk.auradot.wms.document.approval.RequestOutcome("i1", "document-approval", "Document approval", null,
                "COMPLETED", "APPROVED", java.time.Instant.parse("2026-10-06T09:30:00Z"), java.util.List.of()));

        deliver("m1", "workflow.instance.completed", "i1", Map.of("endStatus", "APPROVED"));

        assertThat(round("i1")).containsEntry("status", "APPROVED").containsEntry("end_status", "APPROVED");
        mvc.perform(get("/documents/{id}", doc).with(user("bob")))
                .andExpect(jsonPath("$.state").value("APPROVED")).andExpect(jsonPath("$.versions[0].label").value("1.0"));
        assertThat(returnedEvents()).isZero();
    }

    @Test
    void theSameMessageTwiceIsHandledOnce() throws Exception {
        deliver("m1", "workflow.instance.completed", "i1", Map.of("endStatus", "REJECTED"));
        Object decidedAt = round("i1").get("decided_at");
        send("i2"); // sent again meanwhile

        deliver("m1", "workflow.instance.completed", "i1", Map.of("endStatus", "REJECTED"));

        assertThat(round("i1").get("decided_at")).isEqualTo(decidedAt);
        assertThat(round("i2")).containsEntry("status", "PENDING");
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(jsonPath("$.state").value("IN_APPROVAL"));
        assertThat(returnedEvents()).isEqualTo(1);
    }

    @Test
    void messagesAboutOtherRequestsOrWithoutWhatIsNeededChangeNothing() throws Exception {
        deliver("m1", "workflow.instance.completed", "some-other-request", Map.of("endStatus", "REJECTED"));
        deliver("m2", "workflow.instance.completed", "i1", null); // no end status at all: not approved
        Map<String, Object> noId = Map.of("type", "workflow.instance.completed", "instanceId", "i2");
        listener.onRequestEnded(MessageBuilder.withBody(json.writeValueAsBytes(noId)).build());

        assertThat(round("i1")).containsEntry("status", "NOT_APPROVED").containsEntry("end_status", null);
        assertThat(returnedEvents()).isEqualTo(1);
    }

    @Test
    void onceTheRequestIsOverItsPeopleNoLongerReachTheDocument() throws Exception {
        mvc.perform(get("/documents/{id}", doc).with(user("erin"))).andExpect(status().isOk());

        deliver("m1", "workflow.instance.completed", "i1", Map.of("endStatus", "REJECTED"));

        mvc.perform(get("/documents/{id}", doc).with(user("erin"))).andExpect(status().isNotFound());
        mvc.perform(get("/documents/{id}/review", doc).with(user("erin"))).andExpect(status().isNotFound());
        mvc.perform(get("/documents/{id}/review", doc).with(user("bob")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_NO_REVIEW"));
    }
}
