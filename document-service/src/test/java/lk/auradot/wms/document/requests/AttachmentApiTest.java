package lk.auradot.wms.document.requests;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.FakeInstances;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestFiles;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class AttachmentApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired FakeInstances instances;
    @Autowired JsonMapper json;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        snapshots.invalidate();
        groups.reset();
        instances.reset();
        instances.grant("carol", "i-1", "expense-request", true);
        instances.grant("bob", "i-1", "expense-request", false);
    }

    Map<String, Object> attachment(String instanceId, String fileName, byte[] body) {
        Map<String, Object> request = new HashMap<>();
        request.put("instanceId", instanceId);
        request.put("fileName", fileName);
        request.put("size", body.length);
        request.put("sha256", TestFiles.sha256(body));
        request.put("mimeType", "text/plain");
        return request;
    }

    String attach(JwtRequestPostProcessor who, String instanceId, String fileName, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        String ticketJson = mvc.perform(post("/uploads").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(attachment(instanceId, fileName, body))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode ticket = json.readTree(ticketJson);
        assertThat(TestFiles.put(URI.create(ticket.get("url").asString()), body, "text/plain")).isEqualTo(200);
        String doc = mvc.perform(post("/uploads/{id}/confirm", ticket.get("uploadId").asString()).with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(doc).get("id").asString();
    }

    @Test
    void attachmentsLiveInTheRequestsSystemFolderAndBelongToTheRequest() throws Exception {
        String doc = attach(user("carol"), "i-1", "Receipt.txt", "taxi 1200");

        String folder = jdbc.sql("SELECT folder_id FROM document WHERE id = :id").param("id", doc).query(String.class).single();
        String processFolder = jdbc.sql("SELECT parent_id FROM folder WHERE id = :id").param("id", folder).query(String.class).single();
        assertThat(jdbc.sql("SELECT name FROM folder WHERE id = :id").param("id", folder).query(String.class).single()).isEqualTo("i-1");
        assertThat(jdbc.sql("SELECT parent_id FROM folder WHERE id = :id").param("id", processFolder).query(String.class).single())
                .isEqualTo(Folders.PROCESSES_ID);
        mvc.perform(get("/documents/{id}", doc).with(user("carol")))
                .andExpect(jsonPath("$.linkedInstanceId").value("i-1"))
                .andExpect(jsonPath("$.inLibrary").value(false))
                .andExpect(jsonPath("$.permission").value("WRITE"));
    }

    @Test
    void whoeverMaySeeTheRequestReadsItsDocumentsAndOnlyAttachersAddMore() throws Exception {
        String doc = attach(user("carol"), "i-1", "Receipt.txt", "taxi 1200");

        mvc.perform(get("/instances/{id}/documents", "i-1").with(user("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Receipt.txt"));
        mvc.perform(get("/documents/{id}", doc).with(user("bob")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permission").value("READ"));
        String version = jdbc.sql("SELECT current_version_id FROM document WHERE id = :id").param("id", doc).query(String.class).single();
        mvc.perform(get("/documents/{id}/versions/{v}/content", doc, version).with(user("bob"))).andExpect(status().isOk());

        mvc.perform(post("/uploads").with(user("bob")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(attachment("i-1", "Mine.txt", "x".getBytes()))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("bob"))).andExpect(status().isForbidden());
    }

    @Test
    void peopleOutsideTheRequestSeeNothing() throws Exception {
        String doc = attach(user("carol"), "i-1", "Receipt.txt", "taxi 1200");

        mvc.perform(get("/documents/{id}", doc).with(user("erin"))).andExpect(status().isNotFound());
        mvc.perform(get("/instances/{id}/documents", "i-1").with(user("erin")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_INSTANCE_NOT_FOUND"));
        mvc.perform(post("/uploads").with(user("erin")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(attachment("i-1", "Mine.txt", "x".getBytes()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/folders/root").with(user("carol"))).andExpect(jsonPath("$.children", hasSize(0)));
        mvc.perform(get("/instances/{id}/documents", "i-1").with(user("dave", "document-admin")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void anUnansweredAccessCheckIsA503ButLibraryDocumentsStillOpen() throws Exception {
        String doc = attach(user("carol"), "i-1", "Receipt.txt", "taxi 1200");
        instances.fail("i-1");

        mvc.perform(get("/documents/{id}", doc).with(user("bob")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DOC_WORKFLOW_UNAVAILABLE"));
        mvc.perform(get("/folders/root").with(user("bob"))).andExpect(status().isOk());
    }

    @Test
    void nobodyUploadsStraightIntoASystemFolder() throws Exception {
        attach(user("carol"), "i-1", "Receipt.txt", "taxi 1200");
        String folder = jdbc.sql("SELECT folder_id FROM document").query(String.class).single();
        Map<String, Object> request = attachment("i-1", "Direct.txt", "x".getBytes());
        request.remove("instanceId");
        request.put("folderId", folder);

        mvc.perform(post("/uploads").with(user("dave", "document-admin")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_SYSTEM_FOLDER"));
    }

    @Test
    void someoneWhoJustClaimedATaskCanAttachAtOnce() throws Exception {
        instances.grant("dan", "i-1", "expense-request", false);
        mvc.perform(get("/instances/{id}/documents", "i-1").with(user("dan"))).andExpect(status().isOk());

        instances.grant("dan", "i-1", "expense-request", true); // dan claimed the approval task
        attach(user("dan"), "i-1", "Quote.txt", "quote");
    }

    @Test
    void workflowServiceIsNeverAskedWhileADocumentRowIsLocked() throws Exception {
        String doc = attach(user("carol"), "i-1", "Receipt.txt", "taxi 1200");
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("carol"))).andExpect(status().isOk());
        mvc.perform(post("/documents/{id}/checkout/cancel", doc).with(user("carol"))).andExpect(status().isOk());
        mvc.perform(patch("/documents/{id}", doc).with(user("carol")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Taxi receipt.txt\"}")).andExpect(status().isOk());
        mvc.perform(delete("/documents/{id}", doc).with(user("dave", "document-admin"))).andExpect(status().isNoContent());

        assertThat(instances.callsInTransaction()).isZero();
    }
}
