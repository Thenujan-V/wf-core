package lk.auradot.wms.document.approval;

import lk.auradot.wms.document.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpRequestOutcomesTest {

    static final String OUTCOME = """
            {"id":"i-1","processKey":"doc-approval","processName":"Document approval","processVersion":2,
             "businessKey":" ","status":"COMPLETED","endStatus":"APPROVED","initiatorId":"bob",
             "startedAt":"2026-10-06T08:00:00Z","endedAt":"2026-10-06T09:30:00Z",
             "steps":[{"name":"Manager approval","nodeId":"m","completedBy":"alice","outcome":"APPROVE",
                       "completedAt":"2026-10-06T09:30:00Z"}]}""";

    MockRestServiceServer server;
    HttpRequestOutcomes outcomes;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        outcomes = new HttpRequestOutcomes(builder, URI.create("http://wf"),
                new ServiceAccountProperties(URI.create("http://kc/token"), "wms-document", "secret"), new MutableClock());
    }

    void signedIn() {
        server.expect(requestTo("http://kc/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"svc-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    void answers(ResponseCreator response) {
        signedIn();
        server.expect(requestTo("http://wf/instances/i-1/outcome")).andRespond(response);
    }

    @Test
    void asksAsTheServiceAndReadsHowTheRequestEnded() {
        signedIn();
        server.expect(requestTo("http://wf/instances/i-1/outcome")).andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess(OUTCOME, MediaType.APPLICATION_JSON));

        RequestOutcome outcome = outcomes.outcome("i-1").orElseThrow();

        assertThat(outcome.processName()).isEqualTo("Document approval");
        assertThat(outcome.endStatus()).isEqualTo("APPROVED");
        assertThat(outcome.endedAt()).isEqualTo(Instant.parse("2026-10-06T09:30:00Z"));
        assertThat(outcome.running()).isFalse();
        assertThat(outcome.reference()).isEqualTo("i-1"); // a blank business key is no reference
        assertThat(outcome.steps()).containsExactly(
                new RequestOutcome.Step("Manager approval", "alice", "APPROVE", Instant.parse("2026-10-06T09:30:00Z")));
        server.verify();
    }

    @Test
    void onlyWorkflowServicesOwnNoSuchRequestIsNoOutcome() {
        answers(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"title\":\"Not Found\",\"status\":404,\"detail\":\"Request not found\",\"code\":\"WF_INSTANCE_NOT_FOUND\"}"));

        assertThat(outcomes.outcome("i-1")).isEmpty();
    }

    /**
     * "No such request" closes a round as not approved, for good. A 404 that does not carry workflow-service's own code
     * is something else: an older workflow-service without this address, a proxy, a wrong base path.
     */
    @Test
    void anyOther404MeansTryAgainLater() {
        answers(withResourceNotFound());
        assertThatThrownBy(() -> outcomes.outcome("i-1")).isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void a404WithSomeoneElsesBodyMeansTryAgainLater() {
        answers(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":404,\"error\":\"Not Found\",\"path\":\"/instances/i-1/outcome\"}"));
        assertThatThrownBy(() -> outcomes.outcome("i-1")).isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void beingRefusedOrFailingMeansTryAgainLater() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.TOO_MANY_REQUESTS}) {
            setUp();
            answers(withStatus(status));
            assertThatThrownBy(() -> outcomes.outcome("i-1")).as(status.toString()).isInstanceOf(ApprovalUnavailableException.class);
        }
        setUp();
        answers(withServerError());
        assertThatThrownBy(() -> outcomes.outcome("i-1")).isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void anAnswerThatIsNotAnOutcomeMeansTryAgainLater() {
        String[] bodies = {
                "",                                                          // nothing at all
                "{}",                                                        // somebody else's JSON
                "{\"id\":\"i-1\",\"status\":null}",                          // no status
                "{\"id\":\"i-1\",\"status\":\"PAUSED\"}",                    // a status this service does not know
                OUTCOME.replace("\"id\":\"i-1\"", "\"id\":\"another\""),     // about another request
        };
        for (String body : bodies) {
            setUp();
            answers(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> outcomes.outcome("i-1")).as(body).isInstanceOf(ApprovalUnavailableException.class);
        }
    }

    @Test
    void notBeingAbleToSignInIsNeverNoSuchRequest() {
        server.expect(requestTo("http://kc/token")).andRespond(withResourceNotFound()); // the realm is not there (yet)

        assertThatThrownBy(() -> outcomes.outcome("i-1")).isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void aRunningOrSuspendedRequestIsRunning() {
        assertThat(new RequestOutcome("i", "k", "n", "REF-1", "ACTIVE", null, null, java.util.List.of()).running()).isTrue();
        assertThat(new RequestOutcome("i", "k", "n", "REF-1", "SUSPENDED", null, null, java.util.List.of()).running()).isTrue();
        assertThat(new RequestOutcome("i", "k", "n", "REF-1", "CANCELLED", null, null, java.util.List.of()).running()).isFalse();
        assertThat(new RequestOutcome("i", "k", "n", "REF-1", "ACTIVE", null, null, java.util.List.of()).reference()).isEqualTo("REF-1");
    }
}
