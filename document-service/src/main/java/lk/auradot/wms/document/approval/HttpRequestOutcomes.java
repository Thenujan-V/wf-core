package lk.auradot.wms.document.approval;

import lk.auradot.wms.common.security.ServiceTokens;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;

/**
 * {@link RequestOutcomes} over workflow-service's REST API, as the wms-document service account.
 * <p>
 * "No such request" closes a round of approval as not approved, for good, so it takes positive proof: a 404 carrying
 * workflow-service's own error code. Every other answer that is not a well-formed outcome of the request asked about
 * (another 404, a refusal, an empty or foreign body, not being able to sign in) is "try again later".
 */
public class HttpRequestOutcomes implements RequestOutcomes {

    private static final String NO_SUCH_REQUEST = "\"WF_INSTANCE_NOT_FOUND\"";
    private static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "COMPLETED", "CANCELLED");

    private final RestClient http;
    private final URI baseUrl;
    private final ServiceTokens tokens;

    public HttpRequestOutcomes(RestClient.Builder builder, URI baseUrl, ServiceAccountProperties account, Clock clock) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.tokens = new ServiceTokens(http, account.tokenUrl(), account.clientId(), account.clientSecret(), clock);
    }

    @Override
    public Optional<RequestOutcome> outcome(String instanceId) {
        String token = token(instanceId);
        URI uri = UriComponentsBuilder.fromUri(baseUrl).pathSegment("instances", instanceId, "outcome").build().encode().toUri();
        RequestOutcome outcome;
        try {
            outcome = http.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(RequestOutcome.class);
        } catch (HttpClientErrorException.NotFound e) {
            if (e.getResponseBodyAsString().contains(NO_SUCH_REQUEST)) {
                return Optional.empty();
            }
            throw unavailable(instanceId, "a 404 that is not workflow-service's \"no such request\" (an older workflow-service, or a wrong address?)", e);
        } catch (RestClientException | IllegalStateException e) {
            throw unavailable(instanceId, e.getMessage(), e);
        }
        if (outcome == null || !instanceId.equals(outcome.id()) || outcome.status() == null || !STATUSES.contains(outcome.status())) {
            throw unavailable(instanceId, "an answer that is not the outcome of this request", null);
        }
        return Optional.of(outcome);
    }

    /** Signing in is its own step: a Keycloak that answers 404 (no realm yet) must never read as "no such request". */
    private String token(String instanceId) {
        try {
            return tokens.get();
        } catch (RuntimeException e) {
            throw unavailable(instanceId, "document-service could not sign in as its service account: " + e.getMessage(), e);
        }
    }

    private static ApprovalUnavailableException unavailable(String instanceId, String why, Throwable cause) {
        return new ApprovalUnavailableException("workflow-service could not say how request " + instanceId + " ended: " + why, cause);
    }
}
