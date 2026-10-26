package lk.auradot.wms.document.upload;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.FakeGroups;
import lk.auradot.wms.document.MutableClock;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestFiles;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.Folders;
import lk.auradot.wms.document.storage.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static lk.auradot.wms.document.TestJwt.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DocumentIntegrationTest
class UploadApiTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FakeGroups groups;
    @Autowired MutableClock clock;
    @Autowired ObjectStore objects;
    @Autowired UploadPurge purge;
    @Autowired JsonMapper json;

    String finance;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        groups.reset();
        clock.set(MutableClock.DEFAULT);
        finance = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO folder VALUES (:id, :root, 'Finance', TRUE, 'dave', NOW(6))")
                .param("id", finance).param("root", Folders.ROOT_ID).update();
        jdbc.sql("INSERT INTO folder_acl VALUES (:id, 'GROUP', 'g-fin', 'WRITE')").param("id", finance).update();
        snapshots.invalidate();
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    Map<String, Object> newFile(String fileName, byte[] body) {
        Map<String, Object> request = new HashMap<>();
        request.put("folderId", finance);
        request.put("fileName", fileName);
        request.put("size", body.length);
        request.put("sha256", TestFiles.sha256(body));
        request.put("mimeType", "text/plain");
        return request;
    }

    Map<String, Object> newVersion(String documentId, byte[] body) {
        Map<String, Object> request = new HashMap<>();
        request.put("documentId", documentId);
        request.put("fileName", "v.txt");
        request.put("size", body.length);
        request.put("sha256", TestFiles.sha256(body));
        request.put("mimeType", "text/plain");
        return request;
    }

    ResultActions startRaw(JwtRequestPostProcessor who, Map<String, Object> request) throws Exception {
        return mvc.perform(post("/uploads").with(who).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)));
    }

    JsonNode start(JwtRequestPostProcessor who, Map<String, Object> request) throws Exception {
        String body = startRaw(who, request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    void upload(JsonNode ticket, byte[] body) {
        assertThat(TestFiles.put(URI.create(ticket.get("url").asString()), body, ticket.get("headers").get("Content-Type").asString()))
                .isEqualTo(200);
    }

    ResultActions confirm(JwtRequestPostProcessor who, JsonNode ticket) throws Exception {
        return mvc.perform(post("/uploads/{id}/confirm", ticket.get("uploadId").asString()).with(who));
    }

    String createDocument(String fileName, String text) throws Exception {
        JsonNode ticket = start(user("carol"), newFile(fileName, bytes(text)));
        upload(ticket, bytes(text));
        String body = confirm(user("carol"), ticket).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    @Test
    void createsADocumentFromAConfirmedUpload() throws Exception {
        byte[] body = bytes("minutes of the meeting");
        JsonNode ticket = start(user("carol"), newFile("Minutes.txt", body));
        assertThat(ticket.get("method").asString()).isEqualTo("PUT");

        upload(ticket, body);
        confirm(user("carol"), ticket)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Minutes.txt"))
                .andExpect(jsonPath("$.folderId").value(finance))
                .andExpect(jsonPath("$.versions[0].label").value("1.0"))
                .andExpect(jsonPath("$.versions[0].size").value(body.length))
                .andExpect(jsonPath("$.versions[0].sha256").value(TestFiles.sha256(body)))
                .andExpect(jsonPath("$.checkedOutBy").doesNotExist());

        assertThat(count("SELECT COUNT(*) FROM outbox WHERE routing_key = 'document.version.uploaded'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM upload WHERE confirmed_at IS NOT NULL")).isEqualTo(1);
    }

    @Test
    void aNewVersionNeedsTheCheckOutAndChecksTheDocumentBackIn() throws Exception {
        String doc = createDocument("Policy.txt", "first");

        startRaw(user("carol"), newVersion(doc, bytes("second"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_NOT_CHECKED_OUT"));

        mvc.perform(post("/documents/{id}/checkout", doc).with(user("carol"))).andExpect(status().isOk());
        Map<String, Object> minor = newVersion(doc, bytes("second"));
        minor.put("comment", "Fixed a typo");
        JsonNode ticket = start(user("carol"), minor);
        upload(ticket, bytes("second"));
        confirm(user("carol"), ticket).andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].label").value("1.1"))
                .andExpect(jsonPath("$.versions[0].comment").value("Fixed a typo"))
                .andExpect(jsonPath("$.checkedOutBy").doesNotExist());

        mvc.perform(post("/documents/{id}/checkout", doc).with(user("carol"))).andExpect(status().isOk());
        Map<String, Object> major = newVersion(doc, bytes("third"));
        major.put("major", true);
        JsonNode next = start(user("carol"), major);
        upload(next, bytes("third"));
        confirm(user("carol"), next).andExpect(jsonPath("$.versions[0].label").value("2.0"));
    }

    @Test
    void refusesUploadsThatDoNotMatchTheirTicket() throws Exception {
        byte[] body = bytes("the real contents");
        JsonNode ticket = start(user("carol"), newFile("Real.txt", body));

        confirm(user("carol"), ticket).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_MISSING"));

        upload(ticket, bytes("the fake contents"));
        confirm(user("carol"), ticket).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_MISMATCH"));
        String key = jdbc.sql("SELECT s3_key FROM upload WHERE id = :id").param("id", ticket.get("uploadId").asString())
                .query(String.class).single();
        assertThat(objects.size(key)).isEmpty();

        JsonNode good = start(user("carol"), newFile("Good.txt", body));
        upload(good, body);
        confirm(user("bob"), good).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_NOT_FOUND"));
        confirm(user("carol"), good).andExpect(status().isOk());
        confirm(user("carol"), good).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_CONFIRMED"));

        JsonNode late = start(user("carol"), newFile("Late.txt", body));
        upload(late, body);
        clock.advance(Duration.ofHours(25));
        confirm(user("carol"), late).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_EXPIRED"));

        assertThat(count("SELECT COUNT(*) FROM document")).isEqualTo(1);
    }

    @Test
    void refusesTicketsThatCouldNeverBeConfirmed() throws Exception {
        byte[] body = bytes("x-content");
        startRaw(user("erin"), newFile("A.txt", body)).andExpect(status().isForbidden());

        Map<String, Object> huge = newFile("Huge.bin", body);
        huge.put("size", 104_857_601L);
        startRaw(user("carol"), huge).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_TOO_LARGE"));

        Map<String, Object> both = newFile("Both.txt", body);
        both.put("documentId", "d-1");
        startRaw(user("carol"), both).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_UPLOAD_TARGET"));

        Map<String, Object> badHash = newFile("Hash.txt", body);
        badHash.put("sha256", "xyz");
        startRaw(user("carol"), badHash).andExpect(status().isBadRequest());

        Map<String, Object> badMime = newFile("Mime.txt", body);
        badMime.put("mimeType", "not a type");
        startRaw(user("carol"), badMime).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOC_MIME_INVALID"));

        Map<String, Object> metadata = newFile("Meta.txt", body);
        metadata.put("metadata", Map.of("x", 1));
        startRaw(user("carol"), metadata).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOC_METADATA_WITHOUT_TYPE"));

        createDocument("Taken.txt", "x-content");
        startRaw(user("carol"), newFile("taken.txt", body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOC_NAME_TAKEN"));
    }

    @Test
    void aVersionConfirmedAfterItsLockWasForcedOpenIsRefused() throws Exception {
        String doc = createDocument("Contract.txt", "v1");
        mvc.perform(post("/documents/{id}/checkout", doc).with(user("carol"))).andExpect(status().isOk());
        JsonNode ticket = start(user("carol"), newVersion(doc, bytes("v2")));
        upload(ticket, bytes("v2"));

        mvc.perform(post("/documents/{id}/checkout/cancel", doc).with(user("dave", "document-admin"))).andExpect(status().isOk());
        confirm(user("carol"), ticket).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOC_NOT_CHECKED_OUT"));

        assertThat(count("SELECT COUNT(*) FROM document_version")).isEqualTo(1);
    }

    @Test
    void purgesUploadsThatWereNeverConfirmed() throws Exception {
        byte[] body = bytes("abandoned");
        JsonNode old = start(user("carol"), newFile("Old.txt", body));
        upload(old, body);
        clock.advance(Duration.ofHours(25));
        JsonNode fresh = start(user("carol"), newFile("Fresh.txt", body));

        assertThat(purge.purge()).isEqualTo(1);

        assertThat(count("SELECT COUNT(*) FROM upload")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM upload").query(String.class).single()).isEqualTo(fresh.get("uploadId").asString());
        String oldKey = "objects/" + old.get("uploadId").asString();
        assertThat(objects.size(oldKey)).isEmpty();
    }

    @Test
    void theUploadLinkCannotChangeAVersionAfterItWasConfirmed() throws Exception {
        byte[] body = bytes("approved wording");
        JsonNode ticket = start(user("carol"), newFile("Wording.txt", body));
        upload(ticket, body);
        confirm(user("carol"), ticket).andExpect(status().isOk());

        // The presigned URL is still valid for a while: using it again must not reach the confirmed version.
        upload(ticket, bytes("swapped wordings"));

        String key = jdbc.sql("SELECT s3_key FROM document_version").query(String.class).single();
        assertThat(key).startsWith("versions/");
        try (var in = objects.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(body);
        }
    }
}
