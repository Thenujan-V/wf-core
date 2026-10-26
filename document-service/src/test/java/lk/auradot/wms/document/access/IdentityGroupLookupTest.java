package lk.auradot.wms.document.access;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IdentityGroupLookupTest {

    MockRestServiceServer server;
    MutableClock clock = new MutableClock();
    IdentityGroupLookup lookup;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var props = new IdentityClientProperties(URI.create("http://idn"), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(60));
        lookup = new IdentityGroupLookup(builder, props, clock);
    }

    @Test
    void readsTheCallersGroupsWithTheirOwnTokenAndCachesThem() {
        server.expect(requestTo("http://idn/me")).andExpect(header("Authorization", "Bearer carol-token"))
                .andRespond(withSuccess("""
                        {"id":"carol","groups":[{"id":"g-fin","name":"Finance","path":"/Finance"}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://idn/me")).andRespond(withSuccess("{\"id\":\"carol\",\"groups\":[]}",
                MediaType.APPLICATION_JSON));

        assertThat(lookup.groupIds("carol", "carol-token")).containsExactly("g-fin");
        assertThat(lookup.groupIds("carol", "carol-token")).containsExactly("g-fin");
        clock.advance(Duration.ofSeconds(61));
        assertThat(lookup.groupIds("carol", "carol-token")).isEmpty();
        server.verify();
    }

    @Test
    void anUnreachableIdentityServiceIsA503() {
        server.expect(requestTo("http://idn/me")).andRespond(withServerError());

        assertThatThrownBy(() -> lookup.groupIds("carol", "t"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("DOC_IDENTITY_UNAVAILABLE"));
    }

    @Test
    void someoneIdentityServiceDoesNotKnowSuchAsAServiceAccountHasNoGroups() {
        server.expect(requestTo("http://idn/me")).andRespond(withResourceNotFound());

        assertThat(lookup.groupIds("service-account-wms-workflow", "svc")).isEmpty();
        assertThat(lookup.groupIds("service-account-wms-workflow", "svc")).isEmpty();
        server.verify();
    }
}
