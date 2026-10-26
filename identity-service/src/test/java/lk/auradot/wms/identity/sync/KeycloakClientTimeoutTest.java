package lk.auradot.wms.identity.sync;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class KeycloakClientTimeoutTest {

    @Test
    void stalledKeycloakFailsFastInsteadOfHangingTheSync() throws Exception {
        // Accepts connections but never answers, like a half-open load balancer.
        try (ServerSocket stalled = new ServerSocket(0)) {
            List<Socket> accepted = new ArrayList<>();
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(stalled.accept());
                    }
                } catch (Exception ignored) {
                    // socket closed at the end of the test
                }
            });
            var props = new KeycloakAdminProperties(URI.create("http://localhost:" + stalled.getLocalPort()),
                    "wms", "wms-identity-sync", "secret", 100, Duration.ofSeconds(1), Duration.ofMillis(300));
            KeycloakAdminClient client = new KeycloakClientConfig().keycloakAdminClient(props, Clock.systemUTC());

            // RestClientException is what SyncController maps to 502 IDN_KEYCLOAK_UNAVAILABLE.
            assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                    assertThatThrownBy(client::listUsers).isInstanceOf(RestClientException.class));

            acceptor.interrupt();
        }
    }
}
