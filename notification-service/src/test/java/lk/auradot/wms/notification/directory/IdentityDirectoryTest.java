package lk.auradot.wms.notification.directory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withForbiddenRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityDirectoryTest {

    MockRestServiceServer server;
    IdentityDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        directory = new IdentityDirectory(builder, new IdentityClientProperties(URI.create("http://idn"), URI.create("http://kc/token"),
                "wms-notification", "secret", Duration.ofSeconds(1), Duration.ofSeconds(1)), Clock.systemUTC());
        server.expect(requestTo("http://kc/token"))
                .andRespond(withSuccess("{\"access_token\":\"svc\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    void looksUpAPersonAsTheServiceAccountAndTreatsAnUnknownIdAsNobody() {
        server.expect(requestTo("http://idn/users/bob")).andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess("""
                        {"id":"bob","username":"bob","displayName":"Bob Manager","email":"bob@example.com","enabled":true}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://idn/users/ghost")).andRespond(withResourceNotFound());

        assertThat(directory.person("bob")).contains(new Person("bob", "Bob Manager", "bob@example.com", true));
        assertThat(directory.person("ghost")).isEmpty();
        server.verify();
    }

    @Test
    void listsTheMembersOfGroupsAndRoles() {
        server.expect(requestTo("http://idn/groups/g-fin/members")).andRespond(withSuccess("""
                [{"id":"bob","displayName":"Bob","email":"bob@example.com","enabled":true},
                 {"id":"old","displayName":"Old","email":null,"enabled":false}]""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://idn/roles/wms-admin/members")).andRespond(withSuccess("""
                [{"id":"alice","displayName":"Alice","email":"alice@example.com","enabled":true}]""", MediaType.APPLICATION_JSON));

        assertThat(directory.groupMembers("g-fin")).extracting(Person::id).containsExactly("bob", "old");
        assertThat(directory.roleMembers("wms-admin")).extracting(Person::email).containsExactly("alice@example.com");
    }

    @Test
    void anIdentityServiceThatCannotAnswerIsAnOutageNotAnEmptyAnswer() {
        server.expect(requestTo("http://idn/users/bob")).andRespond(withServerError());

        assertThatThrownBy(() -> directory.person("bob")).isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void anIdTheIdentityServiceRefusesAsMalformedIsNobodyButBeingRefusedEntryIsAnOutage() {
        server.expect(requestTo("http://idn/users/N%2FA")).andRespond(withBadRequest());
        server.expect(requestTo("http://idn/groups/bad%20id/members")).andRespond(withBadRequest());
        server.expect(requestTo("http://idn/users/bob")).andRespond(withForbiddenRequest());

        assertThat(directory.person("N/A")).isEmpty();
        assertThat(directory.groupMembers("bad id")).isEmpty();
        assertThatThrownBy(() -> directory.person("bob")).isInstanceOf(DirectoryUnavailableException.class);
    }
}
