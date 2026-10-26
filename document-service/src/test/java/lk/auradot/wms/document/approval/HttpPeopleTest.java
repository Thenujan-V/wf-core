package lk.auradot.wms.document.approval;

import lk.auradot.wms.document.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpPeopleTest {

    MockRestServiceServer server;
    HttpPeople people;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        people = new HttpPeople(builder, URI.create("http://id"),
                new ServiceAccountProperties(URI.create("http://kc/token"), "wms-document", "secret"), new MutableClock());
    }

    void signedIn() {
        server.expect(requestTo("http://kc/token"))
                .andRespond(withSuccess("{\"access_token\":\"svc-token\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    void answers(String userId, ResponseCreator response) {
        server.expect(requestTo("http://id/users/" + userId)).andRespond(response);
    }

    @Test
    void givesThePersonsNameAsTheServiceSeesIt() {
        signedIn();
        server.expect(requestTo("http://id/users/u-1")).andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess("{\"id\":\"u-1\",\"displayName\":\"Bob Builder\",\"email\":\"bob@example.com\",\"enabled\":true}",
                        MediaType.APPLICATION_JSON));

        assertThat(people.displayName("u-1")).isEqualTo("Bob Builder");
    }

    @Test
    void someoneIdentityDoesNotKnowOrHasNoNameIsShownByTheirId() {
        signedIn();
        answers("ghost", withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"title\":\"Not Found\",\"status\":404,\"detail\":\"User not found\",\"code\":\"IDN_USER_NOT_FOUND\"}"));
        answers("blank", withSuccess("{\"id\":\"blank\",\"displayName\":\" \"}", MediaType.APPLICATION_JSON));

        assertThat(people.displayName("ghost")).isEqualTo("ghost");
        assertThat(people.displayName("blank")).isEqualTo("blank");
    }

    /** An id printed where a name belongs stays in an approved PDF for good, so only identity-service's own "no such person" earns it. */
    @Test
    void anyOther404OrAnEmptyAnswerMeansTryAgainLater() {
        signedIn();
        answers("u-1", withResourceNotFound());
        assertThatThrownBy(() -> people.displayName("u-1")).isInstanceOf(ApprovalUnavailableException.class);

        setUp();
        signedIn();
        answers("u-1", withSuccess("", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> people.displayName("u-1")).isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void beingRefusedOrFailingMeansTryAgainLater() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN}) {
            setUp();
            signedIn();
            answers("u-1", withStatus(status));
            assertThatThrownBy(() -> people.displayName("u-1")).as(status.toString()).isInstanceOf(ApprovalUnavailableException.class);
        }
        setUp();
        signedIn();
        answers("u-1", withServerError());
        assertThatThrownBy(() -> people.displayName("u-1")).isInstanceOf(ApprovalUnavailableException.class);
    }

    @Test
    void notBeingAbleToSignInIsNeverAnUnknownPerson() {
        server.expect(requestTo("http://kc/token")).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> people.displayName("u-1")).isInstanceOf(ApprovalUnavailableException.class);
    }
}
