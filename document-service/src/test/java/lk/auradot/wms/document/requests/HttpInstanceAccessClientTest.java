package lk.auradot.wms.document.requests;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.MutableClock;
import lk.auradot.wms.document.access.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpInstanceAccessClientTest {

    MockRestServiceServer server;
    MutableClock clock = new MutableClock();
    HttpInstanceAccessClient client;
    final Caller bob = new Caller("bob", Set.of("user"), Set.of(), "bob-token");

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpInstanceAccessClient(builder, new WorkflowClientProperties(URI.create("http://wf"), Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(60)), clock);
    }

    @Test
    void asksWithTheCallersOwnTokenAndRemembersTheAnswerForAMinute() {
        server.expect(requestTo("http://wf/instances/i-1/access-check")).andExpect(header("Authorization", "Bearer bob-token"))
                .andRespond(withSuccess("""
                        {"instanceId":"i-1","processKey":"expense","status":"ACTIVE","canView":true,"canAttach":false}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://wf/instances/i-1/access-check")).andRespond(withResourceNotFound());

        assertThat(client.access("i-1", bob)).hasValueSatisfying(a -> {
            assertThat(a.processKey()).isEqualTo("expense");
            assertThat(a.canAttach()).isFalse();
        });
        assertThat(client.access("i-1", bob)).isPresent();
        clock.advance(Duration.ofSeconds(61));
        assertThat(client.access("i-1", bob)).isEmpty();
        server.verify();
    }

    @Test
    void anUnreachableWorkflowServiceIsA503NotANotFound() {
        server.expect(requestTo("http://wf/instances/i-1/access-check")).andRespond(withServerError());

        assertThatThrownBy(() -> client.access("i-1", bob))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_WORKFLOW_UNAVAILABLE"));
    }

    @Test
    void aFreshAnswerIsAskedForAgainAndReplacesTheRememberedOne() {
        server.expect(ExpectedCount.twice(), requestTo("http://wf/instances/i-1/access-check")).andRespond(withSuccess("""
                {"instanceId":"i-1","processKey":"expense","status":"ACTIVE","canView":true,"canAttach":true}""",
                MediaType.APPLICATION_JSON));

        assertThat(client.access("i-1", bob)).isPresent();
        assertThat(client.fresh("i-1", bob)).isPresent();
        assertThat(client.access("i-1", bob)).isPresent();
        server.verify();
    }

    @Test
    void expiredAnswersAreForgottenOnceManyAreRemembered() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer many = MockRestServiceServer.bindTo(builder).build();
        many.expect(ExpectedCount.manyTimes(), requestTo(org.hamcrest.Matchers.startsWith("http://wf/instances/")))
                .andRespond(withResourceNotFound());
        HttpInstanceAccessClient small = new HttpInstanceAccessClient(builder, new WorkflowClientProperties(URI.create("http://wf"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(60)), clock, 3);

        for (int i = 0; i < 4; i++) {
            small.access("i-" + i, bob);
        }
        clock.advance(Duration.ofSeconds(61));
        small.access("i-new", bob);

        assertThat(small.remembered()).isEqualTo(1);
    }
}
