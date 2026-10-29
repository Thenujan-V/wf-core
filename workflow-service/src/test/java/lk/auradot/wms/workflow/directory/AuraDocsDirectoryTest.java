package lk.auradot.wms.workflow.directory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
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

class AuraDocsDirectoryTest {

    private MockRestServiceServer server;
    private AuraDocsDirectory directory;

    private AuraDocsDirectory directory(Duration cacheTtl) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new AuraDocsDirectoryProperties(URI.create("http://api/integration/v1/directory"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), cacheTtl);
        var credentials = new IdentityClientProperties(null, URI.create("http://kc/token"), "wms-workflow", "secret",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        var built = new AuraDocsDirectory(builder, props, credentials,
                Clock.fixed(Instant.parse("2026-10-28T09:00:00Z"), ZoneOffset.UTC));
        server.expect(requestTo("http://kc/token")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"access_token\":\"svc\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        return built;
    }

    @BeforeEach
    void setUp() {
        directory = directory(Duration.ZERO);
    }

    @Test
    void readsUsersFromTheAuraDocsEnvelope() {
        server.expect(requestTo("http://api/integration/v1/directory/users/nimal.perera"))
                .andExpect(header("Authorization", "Bearer svc"))
                .andRespond(withSuccess("""
                        {"status":200,"message":"DATA_RETRIEVED","data":{"id":"nimal.perera","displayName":"Nimal Perera",
                         "email":"nimal@acme.lk","department":"Risk","active":true,"groupIds":["dept:4","grp:G7"]}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://api/integration/v1/directory/users/nimal.perera")).andRespond(withSuccess(
                "{\"status\":200,\"data\":{\"id\":\"nimal.perera\",\"active\":false,\"groupIds\":[]}}",
                MediaType.APPLICATION_JSON));

        assertThat(directory.profile("nimal.perera")).containsEntry("username", "nimal.perera")
                .containsEntry("department", "Risk").containsEntry("displayName", "Nimal Perera");
        assertThat(directory.isActiveUser("nimal.perera")).isFalse();
        server.verify();
    }

    @Test
    void readsGroupAndRoleMembersAndGroupsOfAUser() {
        server.expect(requestTo("http://api/integration/v1/directory/groups/dept:4/members")).andRespond(withSuccess(
                "{\"status\":200,\"data\":[\"kamala.silva\",\"ruwan\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://api/integration/v1/directory/roles/COMPLIANCE_MANAGER/members")).andRespond(
                withSuccess("{\"status\":200,\"data\":[\"ruwan\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://api/integration/v1/directory/users/kamala.silva")).andRespond(withSuccess(
                "{\"status\":200,\"data\":{\"id\":\"kamala.silva\",\"active\":true,\"groupIds\":[\"dept:4\"]}}",
                MediaType.APPLICATION_JSON));

        assertThat(directory.groupMembers("dept:4")).containsExactly("kamala.silva", "ruwan");
        assertThat(directory.roleMembers("COMPLIANCE_MANAGER")).containsExactly("ruwan");
        assertThat(directory.groupIdsOf("kamala.silva")).containsExactly("dept:4");
        server.verify();
    }

    @Test
    void auraDocsHasNoReportingLineSoNobodyIsAManager() {
        assertThat(directory.managers("nimal.perera", 1)).isEmpty();
    }

    @Test
    void answersAreCachedForTheirTimeToLive() {
        directory = directory(Duration.ofSeconds(60));
        server.expect(ExpectedCount.once(), requestTo("http://api/integration/v1/directory/roles/AUDITOR/members"))
                .andRespond(withSuccess("{\"status\":200,\"data\":[\"ruwan\"]}", MediaType.APPLICATION_JSON));

        assertThat(directory.roleMembers("AUDITOR")).containsExactly("ruwan");
        assertThat(directory.roleMembers("AUDITOR")).containsExactly("ruwan");
        server.verify();
    }

    @Test
    void notFoundMeansNobodyButOtherErrorsMeanUnavailable() {
        server.expect(ExpectedCount.times(2), requestTo("http://api/integration/v1/directory/users/ghost"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://api/integration/v1/directory/groups/dept:9/members"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(directory.isActiveUser("ghost")).isFalse();
        assertThat(directory.profile("ghost")).isEmpty();
        assertThatThrownBy(() -> directory.groupMembers("dept:9")).isInstanceOf(DirectoryUnavailableException.class);
    }
}
