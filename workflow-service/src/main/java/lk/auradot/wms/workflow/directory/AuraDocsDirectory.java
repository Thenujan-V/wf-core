package lk.auradot.wms.workflow.directory;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lk.auradot.wms.common.security.ServiceTokens;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link Directory} backed by AuraDOCS (API-Service {@code /integration/v1/directory}), so users, departments,
 * groups and roles stay owned by AuraDOCS. User ids are AuraDOCS usernames; group ids are namespaced
 * ({@code dept:<id>}, {@code grp:<id>}). AuraDOCS has no reporting line, so manager lookups return nobody and
 * manager-assigned tasks surface as unassignable.
 */
public class AuraDocsDirectory implements Directory {

    /** AuraDOCS answers in its {@code {status, message, data}} envelope. */
    record Envelope<T>(Integer status, String message, T data) {
    }

    record DirectoryUser(String id, String displayName, String email, String department, boolean active,
                         List<String> groupIds) {
    }

    private static final ParameterizedTypeReference<Envelope<List<String>>> IDS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Envelope<DirectoryUser>> USER = new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final URI baseUrl;
    private final ServiceTokens tokens;
    private final TtlCache cache;
    private final CircuitBreaker breaker = CircuitBreaker.of("auradocs-directory", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build());

    public AuraDocsDirectory(RestClient.Builder builder, AuraDocsDirectoryProperties props,
                             IdentityClientProperties credentials, Clock clock) {
        this.http = builder.build();
        this.baseUrl = props.baseUrl();
        this.tokens = new ServiceTokens(http, credentials.tokenUrl(), credentials.clientId(),
                credentials.clientSecret(), clock);
        this.cache = new TtlCache(props.cacheTtl(), clock);
    }

    @Override
    public List<String> managers(String userId, int levels) {
        return List.of();
    }

    @Override
    public List<String> groupMembers(String groupId) {
        return cache.get("group:" + groupId, () -> ids(uri(b -> b.pathSegment("groups", groupId, "members"))));
    }

    @Override
    public List<String> roleMembers(String role) {
        return cache.get("role:" + role, () -> ids(uri(b -> b.pathSegment("roles", role, "members"))));
    }

    @Override
    public Map<String, Object> profile(String userId) {
        DirectoryUser user = user(userId);
        Map<String, Object> profile = new LinkedHashMap<>();
        if (user != null) {
            profile.put("id", user.id());
            profile.put("username", user.id());
            profile.put("displayName", user.displayName());
            profile.put("email", user.email());
            profile.put("department", user.department());
        }
        return profile;
    }

    @Override
    public boolean isActiveUser(String userId) {
        DirectoryUser user = user(userId);
        return user != null && user.active();
    }

    @Override
    public List<String> groupIdsOf(String userId) {
        DirectoryUser user = user(userId);
        return user == null || user.groupIds() == null ? List.of() : user.groupIds();
    }

    private DirectoryUser user(String userId) {
        return cache.get("user:" + userId, () -> {
            Envelope<DirectoryUser> body = get(uri(b -> b.pathSegment("users", userId)), USER);
            return body == null ? null : body.data();
        });
    }

    private List<String> ids(URI uri) {
        Envelope<List<String>> body = get(uri, IDS);
        return body == null || body.data() == null ? List.of() : body.data();
    }

    private URI uri(Consumer<UriComponentsBuilder> path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(baseUrl);
        path.accept(builder);
        return builder.build().encode().toUri();
    }

    /** Null on 404; any other failure means the AuraDOCS directory is unavailable. */
    private <T> T get(URI uri, ParameterizedTypeReference<T> type) {
        try {
            return breaker.executeSupplier(() -> {
                try {
                    return http.get().uri(uri).headers(h -> h.setBearerAuth(tokens.get())).retrieve().body(type);
                } catch (HttpClientErrorException.NotFound e) {
                    return null;
                }
            });
        } catch (CallNotPermittedException e) {
            throw new DirectoryUnavailableException("AuraDOCS directory circuit is open after repeated failures", e);
        } catch (RestClientException | IllegalStateException e) {
            throw new DirectoryUnavailableException("AuraDOCS directory call failed: " + uri.getPath(), e);
        }
    }
}
