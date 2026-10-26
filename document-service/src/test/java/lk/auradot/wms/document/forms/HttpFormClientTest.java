package lk.auradot.wms.document.forms;

import lk.auradot.wms.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpFormClientTest {

    MockRestServiceServer server;
    HttpFormClient client;
    final FormRef invoice = new FormRef("invoice-meta", 2);

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpFormClient(builder, new FormClientProperties(URI.create("http://forms"), Duration.ofSeconds(1),
                Duration.ofSeconds(1)));
    }

    @Test
    void validatesWithTheCallersTokenAndReturnsTheCleanedValues() {
        server.expect(requestTo("http://forms/validate")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer t"))
                .andExpect(content().json("{\"formKey\":\"invoice-meta\",\"version\":2,\"data\":{\"amount\":5}}"))
                .andRespond(withSuccess("{\"valid\":true,\"errors\":[],\"data\":{\"amount\":5}}", MediaType.APPLICATION_JSON));

        assertThat(client.validate(invoice, Map.of("amount", 5), "t")).containsEntry("amount", 5);
        server.verify();
    }

    @Test
    void invalidMetadataIsA400WithTheFieldErrors() {
        server.expect(requestTo("http://forms/validate")).andRespond(withSuccess("""
                {"valid":false,"errors":[{"field":"amount","code":"REQUIRED","message":"Amount is required"}],"data":{}}""",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.validate(invoice, Map.of(), "t"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("DOC_METADATA_INVALID");
                    assertThat(e.properties().get("errors")).asString().contains("REQUIRED");
                });
    }

    @Test
    void aMissingFormIsAConflictAndAnUnreachableServiceA503() {
        server.expect(requestTo("http://forms/validate")).andRespond(withResourceNotFound());
        server.expect(requestTo("http://forms/validate")).andRespond(withServerError());

        assertThatThrownBy(() -> client.validate(invoice, Map.of(), "t"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_TYPE_FORM_MISSING"));
        assertThatThrownBy(() -> client.validate(invoice, Map.of(), "t"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_FORM_SERVICE_UNAVAILABLE"));
    }

    @Test
    void aFormIsUsableOnlyWhenThatVersionIsPublished() {
        server.expect(requestTo("http://forms/definitions/invoice-meta/versions/2"))
                .andRespond(withSuccess("{\"key\":\"invoice-meta\",\"version\":2,\"status\":\"PUBLISHED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://forms/definitions/invoice-meta/versions/2"))
                .andRespond(withSuccess("{\"key\":\"invoice-meta\",\"version\":2,\"status\":\"RETIRED\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://forms/definitions/invoice-meta/versions/2")).andRespond(withResourceNotFound());

        assertThat(client.isPublished(invoice, "t")).isTrue();
        assertThat(client.isPublished(invoice, "t")).isFalse();
        assertThat(client.isPublished(invoice, "t")).isFalse();
    }
}
