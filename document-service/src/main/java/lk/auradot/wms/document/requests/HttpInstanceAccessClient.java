package lk.auradot.wms.document.requests;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.access.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Asks workflow-service's access-check with the caller's own token, and keeps each answer about a minute. */
public class HttpInstanceAccessClient implements InstanceAccessClient {

    private record Cached(Optional<InstanceAccess> access, Instant expiresAt) {
    }

    /** Past this many remembered answers, expired ones are dropped, so the map does not grow for ever. */
    static final int MAX_REMEMBERED = 10_000;

    private final RestClient http;
    private final WorkflowClientProperties props;
    private final Clock clock;
    private final int maxRemembered;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public HttpInstanceAccessClient(RestClient.Builder builder, WorkflowClientProperties props, Clock clock) {
        this(builder, props, clock, MAX_REMEMBERED);
    }

    HttpInstanceAccessClient(RestClient.Builder builder, WorkflowClientProperties props, Clock clock, int maxRemembered) {
        this.http = builder.baseUrl(props.baseUrl().toString()).build();
        this.props = props;
        this.clock = clock;
        this.maxRemembered = maxRemembered;
    }

    @Override
    public Optional<InstanceAccess> access(String instanceId, Caller caller) {
        Cached cached = cache.get(caller.userId() + "|" + instanceId);
        if (cached != null && clock.instant().isBefore(cached.expiresAt())) {
            return cached.access();
        }
        return fresh(instanceId, caller);
    }

    @Override
    public Optional<InstanceAccess> fresh(String instanceId, Caller caller) {
        String key = caller.userId() + "|" + instanceId;
        Instant now = clock.instant();
        Optional<InstanceAccess> access;
        try {
            access = Optional.ofNullable(http.get().uri("/instances/{id}/access-check", instanceId)
                    .header("Authorization", "Bearer " + caller.token()).retrieve().body(InstanceAccess.class));
        } catch (HttpClientErrorException.NotFound e) {
            access = Optional.empty();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOC_WORKFLOW_UNAVAILABLE",
                    "Requests cannot be checked right now; please try again shortly");
        }
        if (cache.size() >= maxRemembered) {
            cache.values().removeIf(c -> !now.isBefore(c.expiresAt()));
        }
        cache.put(key, new Cached(access, now.plus(props.cacheTtl())));
        return access;
    }

    /** How many answers are remembered now. */
    int remembered() {
        return cache.size();
    }
}
