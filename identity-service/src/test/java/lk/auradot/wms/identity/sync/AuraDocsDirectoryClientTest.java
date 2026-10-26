package lk.auradot.wms.identity.sync;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AuraDocsDirectoryClientTest {

    @Test
    void readsTheDirectoryWithTheSyncToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var props = new AuraDocsProperties(URI.create("http://docs:9191"), "/integration/wms/directory", null,
                Duration.ofSeconds(5), Duration.ofSeconds(30));
        var client = new AuraDocsDirectoryClient(builder, props, () -> "t1");
        server.expect(requestTo("http://docs:9191/integration/wms/directory"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer t1"))
                .andRespond(withSuccess("""
                        {"users":[{"userId":"alice","email":"alice@acme.com","name":"Alice Perera","active":true,
                                   "groupIds":["G001"],"departmentIds":[3],"roles":["ROLE_ADMIN"],"extra":1}],
                         "groups":[{"id":"G001","name":"Finance","active":true}],
                         "departments":[{"id":3,"name":"Finance"}]}""", MediaType.APPLICATION_JSON));

        AuraDocsDirectory directory = client.fetch();

        server.verify();
        assertThat(directory.users()).singleElement().satisfies(u -> {
            assertThat(u.departmentIds()).containsExactly("3");
            assertThat(u.groupIds()).containsExactly("G001");
            assertThat(u.roles()).containsExactly("ROLE_ADMIN");
        });
        assertThat(directory.departments()).containsExactly(new AuraDocsDirectory.Department("3", "Finance"));
        assertThat(directory.groups()).containsExactly(new AuraDocsDirectory.Group("G001", "Finance", true));
    }

    @Test
    void theAuraDocsProfileBindsTheRoleMapAsWritten() throws Exception {
        var sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("auradocs", new ClassPathResource("application-auradocs.yml"))
                .forEach(sources::addLast);
        var binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));

        AuraDocsProperties props = binder.bind("wms.identity.auradocs", AuraDocsProperties.class).get();

        assertThat(props.baseUrl()).isEqualTo(URI.create("http://localhost:9191"));
        assertThat(props.directoryPath()).isEqualTo("/integration/wms/directory");
        assertThat(props.roleMap()).isEqualTo(Map.of("ROLE_ADMIN", List.of("wms-admin")));
        assertThat(binder.bind("wms.identity.keycloak.realm", String.class).get()).isEqualTo("auraDOCS");
        assertThat(binder.bind("wms.identity.source", String.class).get()).isEqualTo("AURADOCS");
    }
}
