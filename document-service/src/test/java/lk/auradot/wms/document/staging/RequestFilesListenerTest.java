package lk.auradot.wms.document.staging;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestUploads;
import lk.auradot.wms.document.access.AclSnapshots;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** workflow.files.submitted: the files of a submitted form become the request's attachments. */
@DocumentIntegrationTest
class RequestFilesListenerTest {

    @Autowired RequestFilesListener listener;
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        snapshots.invalidate();
        groups.reset();
        instances.reset();
    }

    String stage(String who, String fileName) throws Exception {
        return TestUploads.upload(mvc, json, user(who), Map.of("staged", true), fileName, "content of " + fileName);
    }

    void deliver(String messageId, String instanceId, String actorId, List<String> documentIds) throws Exception {
        Map<String, Object> event = Map.of("messageId", messageId, "type", "workflow.files.submitted",
                "instanceId", instanceId, "processKey", "expense-request", "actorId", actorId, "documentIds", documentIds);
        listener.onFilesSubmitted(MessageBuilder.withBody(json.writeValueAsBytes(event)).build());
    }

    String column(String column, String documentId) {
        return jdbc.sql("SELECT " + column + " FROM document WHERE id = :id").param("id", documentId).query(String.class).single();
    }

    long linkedEvents() {
        return jdbc.sql("SELECT COUNT(*) FROM outbox WHERE routing_key = 'document.linked'").query(Long.class).single();
    }

    @Test
    void submittedFilesMoveIntoTheRequestsFolderAndBelongToTheRequest() throws Exception {
        String doc = stage("carol", "Receipt.txt");
        String stagingFolder = column("folder_id", doc);

        deliver("m1", "i-1", "carol", List.of(doc));

        String folder = column("folder_id", doc);
        assertThat(jdbc.sql("SELECT name FROM folder WHERE id = :id").param("id", folder).query(String.class).single()).isEqualTo("i-1");
        assertThat(column("linked_instance_id", doc)).isEqualTo("i-1");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM folder WHERE id = :id").param("id", stagingFolder).query(Long.class).single()).isZero();
        assertThat(linkedEvents()).isEqualTo(1);
        instances.grant("bob", "i-1", "expense-request", false);
        mvc.perform(get("/documents/{id}", doc).with(user("bob"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.linkedInstanceId").value("i-1"));
    }

    @Test
    void theSameMessageTwiceAttachesOnce() throws Exception {
        String doc = stage("carol", "Receipt.txt");
        deliver("m1", "i-1", "carol", List.of(doc));
        deliver("m1", "i-1", "carol", List.of(doc));
        deliver("m2", "i-1", "carol", List.of(doc)); // a later step sends the same, already attached file again

        assertThat(linkedEvents()).isEqualTo(1);
    }

    @Test
    void aNameAlreadyInTheRequestGetsANumber() throws Exception {
        String first = stage("carol", "Receipt.txt");
        deliver("m1", "i-1", "carol", List.of(first));
        String second = stage("carol", "Receipt.txt");
        deliver("m2", "i-1", "carol", List.of(second));

        assertThat(column("name", first)).isEqualTo("Receipt.txt");
        assertThat(column("name", second)).isEqualTo("Receipt (2).txt");
    }

    @Test
    void onlyTheSubmittersOwnStagedFilesMoveAndMissingOnesAreSkipped() throws Exception {
        String bobs = stage("bob", "Other.txt");
        String stagingFolder = column("folder_id", bobs);

        deliver("m1", "i-1", "carol", List.of(bobs, "00000000-0000-0000-0000-00000000dead"));

        assertThat(column("folder_id", bobs)).isEqualTo(stagingFolder);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM document WHERE id = :id AND linked_instance_id IS NULL").param("id", bobs)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aFileStagedThroughAnotherInstanceIsRecognisedAtOnce() throws Exception {
        snapshots.current(); // this instance has loaded its folder tree; another one takes the upload
        String folder = java.util.UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO folder VALUES (:id, :staging, :id, FALSE, 'system', NOW(6))")
                .param("id", folder).param("staging", lk.auradot.wms.document.folder.Folders.STAGING_ID).update();
        String doc = lk.auradot.wms.document.TestDocuments.create(jdbc, folder, "Receipt.txt", "carol", "1.0");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/staged-files/verify")
                        .with(user("service-account-wms-form", "wms-service"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("submittedBy", "carol", "documentIds", List.of(doc)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.files[0].documentId").value(doc));
        deliver("m1", "i-1", "carol", List.of(doc));
        assertThat(column("linked_instance_id", doc)).isEqualTo("i-1");
    }
}
