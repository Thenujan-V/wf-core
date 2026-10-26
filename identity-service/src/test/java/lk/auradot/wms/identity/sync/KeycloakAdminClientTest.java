package lk.auradot.wms.identity.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KeycloakAdminClientTest {

    private static final String BASE = "http://kc";
    private MockRestServiceServer server;
    private KeycloakAdminClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new KeycloakAdminProperties(URI.create(BASE), "wms", "wms-identity-sync", "secret", 2,
                Duration.ofSeconds(5), Duration.ofSeconds(30));
        client = new KeycloakAdminClient(builder, props, Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC));
    }

    private void expectToken(String token, int expiresIn) {
        server.expect(requestTo(BASE + "/realms/wms/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of(
                        "grant_type", "client_credentials", "client_id", "wms-identity-sync", "client_secret", "secret")))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + "}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void pagesThroughUsersReusingOneToken() {
        expectToken("t1", 300);
        server.expect(requestTo(BASE + "/admin/realms/wms/users?first=0&max=2&briefRepresentation=false"))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("""
                        [{"id":"1","username":"alice","enabled":true,"attributes":{"department":["Management"]}},
                         {"id":"2","username":"bob","enabled":true,"attributes":{"manager":["  alice "]}}]""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/admin/realms/wms/users?first=2&max=2&briefRepresentation=false"))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("[{\"id\":\"3\",\"username\":\"carol\",\"enabled\":false,\"unknownField\":1}]",
                        MediaType.APPLICATION_JSON));

        var users = client.listUsers();

        server.verify();
        assertThat(users).extracting(KcUser::username).containsExactly("alice", "bob", "carol");
        assertThat(users.get(0).attribute("department")).isEqualTo("Management");
        assertThat(users.get(1).attribute("manager")).isEqualTo("alice");
        assertThat(users.get(2).attribute("manager")).isNull();
        assertThat(users.get(2).enabled()).isFalse();
    }

    @Test
    void walksGroupTreeBreadthFirst() {
        expectToken("t1", 300);
        server.expect(requestTo(BASE + "/admin/realms/wms/groups?first=0&max=2&briefRepresentation=false"))
                .andRespond(withSuccess("""
                        [{"id":"g1","name":"Finance","path":"/Finance","subGroupCount":0},
                         {"id":"g2","name":"IT","path":"/IT","subGroupCount":1}]""", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/admin/realms/wms/groups?first=2&max=2&briefRepresentation=false"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/admin/realms/wms/groups/g2/children?first=0&max=2&briefRepresentation=false"))
                .andRespond(withSuccess("[{\"id\":\"g3\",\"name\":\"Support\",\"path\":\"/IT/Support\",\"subGroupCount\":0}]",
                        MediaType.APPLICATION_JSON));

        var groups = client.listGroups();

        server.verify();
        assertThat(groups).extracting(KcGroup::path).containsExactly("/Finance", "/IT", "/IT/Support");
    }

    @Test
    void readsMembersAndEffectiveRoles() {
        expectToken("t1", 300);
        server.expect(requestTo(BASE + "/admin/realms/wms/groups/g1/members?first=0&max=2&briefRepresentation=true"))
                .andRespond(withSuccess("[{\"id\":\"2\",\"username\":\"bob\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/admin/realms/wms/users/2/role-mappings/realm/composite"))
                .andRespond(withSuccess("[{\"name\":\"user\"},{\"name\":\"process-designer\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.groupMemberIds("g1")).containsExactly("2");
        assertThat(client.effectiveRealmRoles("2")).containsExactly("user", "process-designer");
        server.verify();
    }

    @Test
    void refetchesTokenThatIsAboutToExpire() {
        expectToken("short1", 10);
        server.expect(requestTo(BASE + "/admin/realms/wms/users/1/role-mappings/realm/composite"))
                .andExpect(header("Authorization", "Bearer short1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        expectToken("short2", 10);
        server.expect(requestTo(BASE + "/admin/realms/wms/users/1/role-mappings/realm/composite"))
                .andExpect(header("Authorization", "Bearer short2"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.effectiveRealmRoles("1");
        client.effectiveRealmRoles("1");

        server.verify();
    }
}
