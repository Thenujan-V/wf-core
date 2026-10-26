package lk.auradot.wms.document.written;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.FakeOutcomes;
import lk.auradot.wms.document.MutableClock;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.approval.RequestOutcome;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A round whose request's "ended" message never arrived is found by asking workflow-service now and then. */
@DocumentIntegrationTest
class ApprovalSweepTest {

    @Autowired ApprovalSettlement settlement;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;
    @Autowired FakeOutcomes outcomes;
    @Autowired MutableClock clock;
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

    RequestOutcome request(String status, String endStatus) {
        return new RequestOutcome("i1", "document-approval", "Document approval", null, status, endStatus,
                endStatus == null ? null : Instant.parse("2026-10-06T09:30:00Z"), List.of());
    }

    String state() throws Exception {
        return json.readTree(mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andReturn().getResponse().getContentAsString())
                .get("state").asString();
    }

    @Test
    void aFreshRoundIsNotAskedAbout() throws Exception {
        outcomes.put(request("COMPLETED", "APPROVED"));

        assertThat(settlement.sweepStale()).isZero();

        assertThat(state()).isEqualTo("IN_APPROVAL");
    }

    @Test
    void anOldRoundWhoseRequestWasApprovedGetsItsVersionWithoutAnyMessage() throws Exception {
        outcomes.put(request("COMPLETED", "APPROVED"));
        clock.advance(Duration.ofMinutes(11));

        assertThat(settlement.sweepStale()).isEqualTo(1);

        assertThat(state()).isEqualTo("APPROVED");
    }

    @Test
    void anOldRoundWhoseRequestWasCancelledOrIsUnknownComesBackAsADraft() throws Exception {
        outcomes.put(request("CANCELLED", null));
        clock.advance(Duration.ofMinutes(11));

        assertThat(settlement.sweepStale()).isEqualTo(1);

        assertThat(state()).isEqualTo("DRAFT");
    }

    @Test
    void anOldRoundWhoseRequestStillRunsIsLeftAloneAndNotAskedAboutAgainForAWhile() throws Exception {
        outcomes.put(request("ACTIVE", null));
        clock.advance(Duration.ofMinutes(11));
        assertThat(settlement.sweepStale()).isZero();
        assertThat(state()).isEqualTo("IN_APPROVAL");

        // The request ends a minute later; the next pass comes too soon to ask again, a later one does.
        outcomes.put(request("COMPLETED", "REJECTED"));
        clock.advance(Duration.ofMinutes(1));
        assertThat(settlement.sweepStale()).isZero();
        clock.advance(Duration.ofMinutes(10));
        assertThat(settlement.sweepStale()).isEqualTo(1);
        assertThat(state()).isEqualTo("DRAFT");
    }

    @Test
    void whenWorkflowCannotAnswerNothingIsDecided() throws Exception {
        outcomes.put(request("CANCELLED", null));
        outcomes.fail();
        clock.advance(Duration.ofMinutes(11));

        assertThat(settlement.sweepStale()).isZero();
        assertThat(state()).isEqualTo("IN_APPROVAL");

        outcomes.recover();
        assertThat(settlement.sweepStale()).isEqualTo(1);
        assertThat(state()).isEqualTo("DRAFT");
    }
}
