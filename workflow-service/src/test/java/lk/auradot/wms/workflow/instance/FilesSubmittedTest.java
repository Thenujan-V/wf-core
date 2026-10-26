package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.StubForms;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestForms;
import lk.auradot.wms.workflow.TestJwt;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §5 file fields: once a start or a task commits, document-service is told which files to attach. */
@WorkflowIntegrationTest
class FilesSubmittedTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper mapper;
    @Autowired DefinitionStore definitions;
    @Autowired StubForms forms;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        forms.reset();
        forms.publish("receipt-claim", 1, "PUBLISHED", TestForms.load("receipt-claim.json"));
        definitions.saveDraft("receipt-review", "Receipt review", TestProcesses.load("receipt-review.json"), "bob");
        definitions.publish("receipt-review", "bob");
    }

    static Map<String, Object> ref(String documentId) {
        return Map.of("documentId", documentId, "name", documentId + ".pdf");
    }

    String start(Map<String, Object> data) throws Exception {
        String body = mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("processKey", "receipt-review", "data", data))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return (String) mapper.readValue(body, Map.class).get("id");
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> announcements() {
        return jdbc.sql("SELECT payload_json FROM outbox WHERE routing_key = 'workflow.files.submitted'").query(String.class)
                .list().stream().map(json -> (Map<String, Object>) mapper.readValue(json, Map.class)).toList();
    }

    @Test
    void theStartFormsFilesAreAnnouncedForTheNewRequest() throws Exception {
        String instanceId = start(Map.of("purpose", "Taxi", "receipts", List.of(ref("d1"))));

        assertThat(announcements()).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("instanceId", instanceId).containsEntry("processKey", "receipt-review")
                    .containsEntry("actorId", "carol").containsEntry("documentIds", List.of("d1"));
            assertThat(event).containsKey("messageId");
        });
    }

    @Test
    void aTaskThatAddsFilesAnnouncesThem() throws Exception {
        String instanceId = start(Map.of("purpose", "Taxi", "receipts", List.of(ref("d1"))));
        String review = jdbc.sql("SELECT id FROM task WHERE instance_id = :i AND node_id = 'review'").param("i", instanceId)
                .query(String.class).single();

        mvc.perform(post("/tasks/{id}/complete", review).with(TestJwt.user("bob", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("outcome", "OK", "data",
                                Map.of("receipts", List.of(ref("d1"), ref("d2")))))))
                .andExpect(status().isNoContent());

        assertThat(announcements()).filteredOn(event -> "bob".equals(event.get("actorId"))).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("documentIds", List.of("d1", "d2")));
    }

    @Test
    void aFormWithoutFilesAnnouncesNothing() throws Exception {
        start(Map.of("purpose", "Taxi"));

        assertThat(announcements()).isEmpty();
    }

    @Test
    void filesThatCannotBeCheckedRightNowAskThePersonToTryAgain() throws Exception {
        forms.filesUnavailableNext();

        mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("processKey", "receipt-review",
                                "data", Map.of("purpose", "Taxi", "receipts", List.of(ref("d1")))))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WF_FILES_UNAVAILABLE"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM process_instance").query(Long.class).single()).isZero();
    }
}
