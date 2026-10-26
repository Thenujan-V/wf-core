package lk.auradot.wms.form.files;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DocumentServiceFilesTest {

    MockRestServiceServer server;
    DocumentServiceFiles files;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        files = new DocumentServiceFiles(builder, new DocumentClientProperties(URI.create("http://doc"), URI.create("http://kc/token"),
                "wms-form", "secret", Duration.ofSeconds(1), Duration.ofSeconds(1)), Clock.systemUTC());
        server.expect(requestTo("http://kc/token"))
                .andRespond(withSuccess("{\"access_token\":\"svc\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    void asksDocumentServiceAsTheFormServiceAccount() {
        server.expect(requestTo("http://doc/staged-files/verify")).andExpect(header("Authorization", "Bearer svc"))
                .andExpect(content().json("{\"submittedBy\":\"carol\",\"documentIds\":[\"d1\",\"d2\"]}"))
                .andRespond(withSuccess("""
                        {"files":[{"documentId":"d1","name":"Taxi.pdf","size":10,"mimeType":"application/pdf"}],"unavailable":["d2"]}""",
                        MediaType.APPLICATION_JSON));

        Verification verification = files.verify("carol", List.of("d1", "d2"));

        assertThat(verification.files()).extracting(VerifiedFile::name).containsExactly("Taxi.pdf");
        assertThat(verification.unavailable()).containsExactly("d2");
        server.verify();
    }

    @Test
    void aFailingDocumentServiceIsReportedAsUnavailable() {
        server.expect(requestTo("http://doc/staged-files/verify")).andRespond(withServerError());

        assertThatThrownBy(() -> files.verify("carol", List.of("d1"))).isInstanceOf(FilesUnavailableException.class);
    }
}
