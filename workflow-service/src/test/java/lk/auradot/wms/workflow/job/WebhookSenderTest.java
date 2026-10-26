package lk.auradot.wms.workflow.job;

import com.sun.net.httpserver.HttpServer;
import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.runtime.ActionRequest;
import lk.auradot.wms.workflow.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSenderTest {

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ok", exchange -> {
            received.add(exchange.getRequestHeaders().getFirst("Idempotency-Key") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/unavailable", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.createContext("/bad", exchange -> {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private WebhookSender sender(boolean allowPrivate) {
        var props = new WebhookProperties(Duration.ofSeconds(1), Duration.ofMillis(300), allowPrivate);
        return new WebhookConfig().webhookSender(props, InetAddress::getAllByName, new Json(JsonMapper.builder().build()));
    }

    private ActionRequest request(String path) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
        return new ActionRequest("job-1", "hook", ActionType.WEBHOOK, Map.of("url", url),
                Map.of("initiator", "carol", "form", Map.of("amount", 5)));
    }

    @Test
    void postsTheVariablesWithAnIdempotencyKey() {
        ActionResult result = sender(true).send(request("/ok"));

        assertThat(result.success()).isTrue();
        assertThat(received).singleElement().satisfies(line -> {
            assertThat(line).startsWith("job-1 ");
            assertThat(line).contains("\"initiator\":\"carol\"").contains("\"nodeId\":\"hook\"");
        });
    }

    @Test
    void classifiesFailures() {
        WebhookSender sender = sender(true);

        assertThat(sender.send(request("/unavailable"))).isEqualTo(ActionResult.failed("Webhook returned HTTP 503", true));
        assertThat(sender.send(request("/bad"))).isEqualTo(ActionResult.failed("Webhook returned HTTP 400", false));
        assertThat(sender.send(request("/redirect")))
                .as("redirects are not followed").isEqualTo(ActionResult.failed("Webhook returned HTTP 302", false));
        assertThat(sender.send(request("/slow"))).satisfies(r -> {
            assertThat(r.success()).isFalse();
            assertThat(r.retryable()).isTrue();
        });
    }

    @Test
    void refusesInternalAddressesWithoutCallingThem() {
        ActionResult result = sender(false).send(request("/ok"));

        assertThat(result.success()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.error()).contains("blocked");
        assertThat(received).isEmpty();
    }

    @Test
    void emailsSucceedImmediately() {
        var runner = new ActionRunner(sender(false));

        assertThat(runner.run(new ActionRequest("j", "n", ActionType.SEND_EMAIL, Map.of(), Map.of())).success()).isTrue();
    }
}
