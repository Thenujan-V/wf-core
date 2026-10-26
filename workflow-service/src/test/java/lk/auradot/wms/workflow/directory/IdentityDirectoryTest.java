package lk.auradot.wms.workflow.directory;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityDirectoryTest {

    private MockRestServiceServer server;
    private IdentityDirectory directory;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new IdentityClientProperties(URI.create("http://idn"), URI.create("http://kc/token"), "wms-workflow",
                "secret", Duration.ofSeconds(1), Duration.ofSeconds(1));
        directory = new IdentityDirectory(builder, props, Clock.fixed(Instant.parse("2026-09-28T09:00:00Z"), ZoneOffset.UTC));
        server.expect(requestTo("http://kc/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"svc\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    void readsManagersProfilesAndActivity() {
        server.expect(requestTo("http://idn/users/carol/managers?levels=2")).andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess("[{\"id\":\"bob\",\"enabled\":true},{\"id\":\"alice\",\"enabled\":true}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://idn/users/carol")).andRespond(withSuccess("""
                {"id":"carol","username":"carol","displayName":"Carol Clerk","email":"carol@example.com",
                 "department":"Finance","position":"Accountant","managerId":"bob","enabled":true,
                 "roles":["user"],"groups":[{"id":"g-fin","name":"Finance","path":"/Finance"}]}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://idn/users/carol")).andRespond(withSuccess(
                "{\"id\":\"carol\",\"enabled\":false,\"groups\":[]}", MediaType.APPLICATION_JSON));

        assertThat(directory.managers("carol", 2)).containsExactly("bob", "alice");
        assertThat(directory.profile("carol")).containsEntry("department", "Finance").containsEntry("managerId", "bob")
                .doesNotContainKey("roles");
        assertThat(directory.isActiveUser("carol")).isFalse();
        server.verify();
    }

    @Test
    void readsGroupAndRoleMembersAndGroupsOfAUser() {
        server.expect(requestTo("http://idn/groups/g-fin/members")).andRespond(withSuccess(
                "[{\"id\":\"bob\",\"enabled\":true},{\"id\":\"old\",\"enabled\":false}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://idn/roles/wms-admin/members")).andRespond(withSuccess(
                "[{\"id\":\"alice\",\"enabled\":true}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://idn/users/bob")).andRespond(withSuccess(
                "{\"id\":\"bob\",\"enabled\":true,\"groups\":[{\"id\":\"g-fin\"},{\"id\":\"g-it\"}]}", MediaType.APPLICATION_JSON));

        assertThat(directory.groupMembers("g-fin")).containsExactly("bob");
        assertThat(directory.roleMembers("wms-admin")).containsExactly("alice");
        assertThat(directory.groupIdsOf("bob")).containsExactly("g-fin", "g-it");
        server.verify();
    }

    @Test
    void notFoundMeansNobodyButOtherErrorsMeanUnavailable() {
        server.expect(requestTo("http://idn/users/ghost")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://idn/groups/nope/members")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://idn/users/carol/managers?levels=1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(directory.isActiveUser("ghost")).isFalse();
        assertThat(directory.groupMembers("nope")).isEmpty();
        assertThatThrownBy(() -> directory.managers("carol", 1)).isInstanceOf(DirectoryUnavailableException.class);
    }

    @Test
    void encodesFederatedIdsAsSinglePathSegments() {
        server.expect(requestTo("http://idn/users/f:c1:CN=Dave%20Perera%2FFinance")).andRespond(withSuccess(
                "{\"id\":\"f:c1:CN=Dave Perera/Finance\",\"enabled\":true,\"groups\":[]}", MediaType.APPLICATION_JSON));

        assertThat(directory.isActiveUser("f:c1:CN=Dave Perera/Finance")).isTrue();
        server.verify();
    }

    @Test
    void repeatedFailuresOpenTheCircuitSoCallsFailFastWithoutReachingIdentity() {
        for (int i = 0; i < 5; i++) {
            server.expect(requestTo("http://idn/users/carol/managers?levels=1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> directory.managers("carol", 1)).isInstanceOf(DirectoryUnavailableException.class);
        }

        assertThatThrownBy(() -> directory.managers("carol", 1))
                .isInstanceOf(DirectoryUnavailableException.class)
                .hasMessageContaining("circuit");
        server.verify();
    }
}
