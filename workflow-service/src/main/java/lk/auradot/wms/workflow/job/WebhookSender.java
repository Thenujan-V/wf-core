package lk.auradot.wms.workflow.job;

import lk.auradot.wms.engine.runtime.ActionRequest;
import lk.auradot.wms.workflow.Json;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Calls a process webhook. Never throws: every outcome becomes an {@link ActionResult}. */
public class WebhookSender {

    private final RestClient http;
    private final WebhookProperties props;
    private final HostResolver resolver;
    private final Json json;

    public WebhookSender(RestClient http, WebhookProperties props, HostResolver resolver, Json json) {
        this.http = http;
        this.props = props;
        this.resolver = resolver;
        this.json = json;
    }

    public ActionResult send(ActionRequest request) {
        URI uri;
        try {
            uri = URI.create(String.valueOf(request.config().get("url")));
        } catch (IllegalArgumentException e) {
            return ActionResult.failed("The webhook URL is invalid", false);
        }
        try {
            Optional<String> blocked = UrlGuard.blocked(uri, props.allowPrivateNetworks(), resolver);
            if (blocked.isPresent()) {
                return ActionResult.failed("Webhook blocked: " + blocked.get(), false);
            }
        } catch (UnknownHostException e) {
            return ActionResult.failed("Webhook host cannot be resolved: " + uri.getHost(), true);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", request.jobId());
        body.put("nodeId", request.nodeId());
        body.put("variables", request.variables());
        try {
            int status = http.post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", request.jobId())
                    .body(json.write(body))
                    .exchange((req, res) -> res.getStatusCode().value());
            if (status >= 200 && status < 300) {
                return ActionResult.ok();
            }
            return ActionResult.failed("Webhook returned HTTP " + status, status == 408 || status == 429 || status >= 500);
        } catch (RestClientException e) {
            return ActionResult.failed("Webhook call failed: " + e.getMessage(), true);
        }
    }
}
