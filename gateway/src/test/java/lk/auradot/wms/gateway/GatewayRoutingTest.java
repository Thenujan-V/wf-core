package lk.auradot.wms.gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/test")
class GatewayRoutingTest {

    /** Stands in for identity-service; like the real services it echoes the correlation id it received. */
    static final DisposableServer DOWNSTREAM = HttpServer.create().port(0)
            .route(routes -> routes.get("/me", (request, response) -> {
                String correlationId = request.requestHeaders().get("X-Correlation-Id");
                return response
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", String.valueOf(correlationId))
                        .header("X-Seen-Correlation", String.valueOf(correlationId))
                        .sendString(Mono.just("{\"ok\":true}"));
            }).get("/ws", (request, response) -> response.sendString(Mono.just("handshake"))))
            .bindNow();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String prefix = "spring.cloud.gateway.server.webflux.routes";
        registry.add(prefix + "[0].id", () -> "identity-service");
        registry.add(prefix + "[0].uri", () -> "http://localhost:" + DOWNSTREAM.port());
        registry.add(prefix + "[0].predicates[0]", () -> "Path=/api/identity/**");
        registry.add(prefix + "[0].filters[0]", () -> "StripPrefix=2");
        // A service that is down: a port that was free a moment ago and has nothing listening.
        registry.add(prefix + "[1].id", () -> "down-service");
        registry.add(prefix + "[1].uri", () -> "http://127.0.0.1:" + closedPort());
        registry.add(prefix + "[1].predicates[0]", () -> "Path=/api/down/**");
        registry.add(prefix + "[2].id", () -> "notification-service");
        registry.add(prefix + "[2].uri", () -> "http://localhost:" + DOWNSTREAM.port());
        registry.add(prefix + "[2].predicates[0]", () -> "Path=/api/notifications/**");
        registry.add(prefix + "[2].filters[0]", () -> "StripPrefix=2");
    }

    static int closedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @AfterAll
    static void stopDownstream() {
        DOWNSTREAM.disposeNow();
    }

    @Autowired
    ApplicationContext context;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }

    @Test
    void rejectsAnonymousApiCallsWithProblemDetail() {
        var result = client.get().uri("/api/identity/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .returnResult();

        String correlationId = result.getResponseHeaders().getFirst("X-Correlation-Id");
        assertThat(correlationId).isNotBlank();
        assertThat(new String(result.getResponseBodyContent())).contains("\"correlationId\":\"" + correlationId + "\"");
    }

    @Test
    void routesAuthenticatedCallsWithStrippedPrefixAndOneCorrelationId() {
        var result = client.mutateWith(mockJwt()).get().uri("/api/identity/me").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"ok\":true}")
                .returnResult();

        var correlationIds = result.getResponseHeaders().get("X-Correlation-Id");
        assertThat(correlationIds).hasSize(1);
        assertThat(result.getResponseHeaders().getFirst("X-Seen-Correlation")).isEqualTo(correlationIds.getFirst());
    }

    @Test
    void keepsSafeIncomingCorrelationIdAndReplacesUnsafeOne() {
        client.mutateWith(mockJwt()).get().uri("/api/identity/me").header("X-Correlation-Id", "client-1234")
                .exchange().expectHeader().valueEquals("X-Seen-Correlation", "client-1234");

        var result = client.mutateWith(mockJwt()).get().uri("/api/identity/me")
                .header("X-Correlation-Id", "x".repeat(100)).exchange().returnResult(String.class);
        assertThat(result.getResponseHeaders().getFirst("X-Seen-Correlation")).hasSize(36);
    }

    @Test
    void deniesUnknownPathsWithProblemDetail() {
        client.mutateWith(mockJwt()).get().uri("/internal/secret").exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("X-Correlation-Id")
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    void reportsUnknownApiRouteAsNotFoundProblem() {
        client.mutateWith(mockJwt()).get().uri("/api/nothing-here/x").exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void reportsDownServiceAsServiceUnavailableProblem() {
        client.mutateWith(mockJwt()).get().uri("/api/down/anything").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists("X-Correlation-Id")
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.detail").isEqualTo("The service is temporarily unavailable. Try again shortly.");
    }

    @Test
    void theLivePushHandshakePassesWithoutATokenAndNothingElseDoes() {
        client.get().uri("/api/notifications/ws").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("handshake");
        client.get().uri("/api/notifications/notifications").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/notifications/ws/more").exchange().expectStatus().isUnauthorized();
    }
}
