package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.workflow.directory.IdentityClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FormServiceClientTest {

    private MockRestServiceServer server;
    private FormServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var credentials = new IdentityClientProperties(URI.create("http://idn"), URI.create("http://kc/token"), "wms-workflow",
                "secret", Duration.ofSeconds(1), Duration.ofSeconds(1));
        var props = new FormClientProperties(URI.create("http://forms"), Duration.ofSeconds(1), Duration.ofSeconds(1));
        client = new FormServiceClient(builder, props, credentials, Clock.fixed(Instant.parse("2026-09-28T09:00:00Z"), ZoneOffset.UTC));
        server.expect(requestTo("http://kc/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"svc\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    void readsPublishedVersionsAndTreatsMissingOnesAsEmpty() {
        server.expect(requestTo("http://forms/definitions/expense-claim/versions/2")).andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess("""
                        {"id":"x","key":"expense-claim","version":2,"name":"Claim","status":"PUBLISHED",
                         "definition":{"fields":[{"key":"amount","type":"currency","label":"Amount"}]}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://forms/definitions/expense-claim/versions/9")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.version("expense-claim", 2)).hasValueSatisfying(v -> {
            assertThat(v.status()).isEqualTo("PUBLISHED");
            assertThat(v.definition()).containsKey("fields");
        });
        assertThat(client.version("expense-claim", 9)).isEmpty();
        server.verify();
    }

    @Test
    void submitsAndReturnsTheCleanedData() {
        server.expect(requestTo("http://forms/submissions")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"formKey":"finance-review","version":1,"data":{"costCentre":"FN-120"},
                         "access":{"amount":"READONLY"},"baseline":{"amount":500},"submittedBy":"alice","contextRef":"task:t1"}"""))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"sub-1\",\"data\":{\"amount\":500,\"costCentre\":\"FN-120\"}}"));

        Submitted submitted = client.submit(new SubmitForm("finance-review", 1, Map.of("costCentre", "FN-120"),
                Map.of("amount", "READONLY"), Map.of("amount", 500), "alice", "task:t1"));

        assertThat(submitted.id()).isEqualTo("sub-1");
        assertThat(submitted.data()).containsEntry("amount", 500).containsEntry("costCentre", "FN-120");
    }

    @Test
    void validationFailuresCarryTheFieldErrors() {
        server.expect(requestTo("http://forms/submissions")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("""
                        {"status":400,"code":"FORM_VALIDATION_FAILED","detail":"The form has 1 problem(s)",
                         "errors":[{"field":"costCentre","code":"PATTERN_MISMATCH","message":"Cost centre is not in the expected format"}]}"""));

        assertThatThrownBy(() -> client.submit(new SubmitForm("finance-review", 1, Map.of(), Map.of(), Map.of(), "alice", null)))
                .isInstanceOfSatisfying(FormValidationException.class, e ->
                        assertThat(e.errors()).singleElement().satisfies(error -> {
                            assertThat(error).containsEntry("field", "costCentre").containsEntry("code", "PATTERN_MISMATCH");
                        }));
    }

    @Test
    void otherFailuresMeanFormServiceIsUnavailable() {
        server.expect(requestTo("http://forms/definitions/f/versions/1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> client.version("f", 1)).isInstanceOf(FormServiceUnavailableException.class);
    }

    @Test
    void refusalsFromFormServiceAreNotOutagesAndDoNotOpenTheCircuit() {
        for (int i = 0; i < 6; i++) {
            server.expect(requestTo("http://forms/definitions/f/versions/1")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"status\":400,\"code\":\"FORM_KEY_INVALID\"}"));
        }

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.version("f", 1)).isInstanceOf(FormServiceRejectedException.class);
        }
        server.verify(); // all six reached form-service: the breaker stayed closed
    }

    @Test
    void filesThatCannotBeCheckedAreNotAFormServiceOutageAndDoNotOpenTheCircuit() {
        for (int i = 0; i < 6; i++) {
            server.expect(requestTo("http://forms/submissions")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body("{\"status\":503,\"code\":\"FORM_FILES_UNAVAILABLE\"}"));
        }
        server.expect(requestTo("http://forms/definitions/f/versions/1")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        SubmitForm request = new SubmitForm("f", 1, Map.of(), Map.of(), Map.of(), "carol", "start:p");

        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> client.submit(request)).isInstanceOf(FormFilesUnavailableException.class);
        }
        assertThat(client.version("f", 1)).isEmpty();
        server.verify(); // the version call reached form-service: the breaker stayed closed
    }
}
