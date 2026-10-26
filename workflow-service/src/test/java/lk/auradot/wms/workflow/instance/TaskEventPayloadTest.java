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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consumers that tell people about a task (notifications) need its name, not only its id. */
@WorkflowIntegrationTest
class TaskEventPayloadTest {

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

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events(String routingKey) {
        return jdbc.sql("SELECT payload_json FROM outbox WHERE routing_key = :key").param("key", routingKey).query(String.class)
                .list().stream().map(json -> (Map<String, Object>) mapper.readValue(json, Map.class)).toList();
    }

    @Test
    void eventsAboutATaskSayWhichStepItIs() throws Exception {
        String body = mvc.perform(post("/instances").with(TestJwt.user("carol", "user")).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("processKey", "receipt-review", "data", Map.of("purpose", "Taxi")))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String instanceId = (String) mapper.readValue(body, Map.class).get("id");
        String review = jdbc.sql("SELECT id FROM task WHERE instance_id = :i").param("i", instanceId).query(String.class).single();

        assertThat(events("workflow.task.created")).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("taskName", "Review"));

        mvc.perform(post("/tasks/{id}/complete", review).with(TestJwt.user("bob", "user")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"OK\"}")).andExpect(status().isNoContent());

        assertThat(events("workflow.task.completed")).singleElement()
                .satisfies(event -> assertThat(event).containsEntry("taskName", "Review"));
        assertThat(events("workflow.instance.completed")).singleElement()
                .satisfies(event -> assertThat(event).doesNotContainKey("taskName"));
    }
}
