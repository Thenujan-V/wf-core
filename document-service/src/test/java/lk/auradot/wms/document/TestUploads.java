package lk.auradot.wms.document;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole upload flow in one call: ticket, presigned PUT to storage, confirm. */
public final class TestUploads {

    private TestUploads() {
    }

    /** Uploads a small text file to {@code target} (e.g. {@code Map.of("staged", true)}); returns the document id. */
    public static String upload(MockMvc mvc, JsonMapper json, RequestPostProcessor who, Map<String, Object> target,
                                String fileName, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        Map<String, Object> request = new HashMap<>(target);
        request.put("fileName", fileName);
        request.put("size", body.length);
        request.put("sha256", TestFiles.sha256(body));
        request.put("mimeType", "text/plain");
        String ticketJson = mvc.perform(post("/uploads").with(who).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(request)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode ticket = json.readTree(ticketJson);
        assertThat(TestFiles.put(URI.create(ticket.get("url").asString()), body, "text/plain")).isEqualTo(200);
        String document = mvc.perform(post("/uploads/{id}/confirm", ticket.get("uploadId").asString()).with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(document).get("id").asString();
    }
}
