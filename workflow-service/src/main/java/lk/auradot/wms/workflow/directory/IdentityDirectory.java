package lk.auradot.wms.workflow.directory;

import lk.auradot.wms.common.security.ServiceTokens;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
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

/** {@link Directory} backed by identity-service's REST API, authenticated as the wms-workflow service account. */
public class IdentityDirectory implements Directory {

    record UserRef(String id, boolean enabled) {
    }

    record GroupRef(String id) {
    }

    record UserDetail(String id, String username, String displayName, String email, String department, String position,
                      String managerId, boolean enabled, List<GroupRef> groups) {
    }

    private static final ParameterizedTypeReference<List<UserRef>> USERS = new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final URI baseUrl;
    private final ServiceTokens tokens;
    /** Spec §2.2: when identity-service keeps failing, fail fast instead of tying up threads and DB connections. */
    private final CircuitBreaker breaker = CircuitBreaker.of("identity-service", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .build());

    public IdentityDirectory(RestClient.Builder builder, IdentityClientProperties props, Clock clock) {
        this.http = builder.build();
        this.baseUrl = props.baseUrl();
        this.tokens = new ServiceTokens(http, props.tokenUrl(), props.clientId(), props.clientSecret(), clock);
    }

    @Override
    public List<String> managers(String userId, int levels) {
        List<UserRef> chain = get(uri(b -> b.pathSegment("users", userId, "managers").queryParam("levels", levels)), USERS);
        return chain == null ? List.of() : chain.stream().map(UserRef::id).toList();
    }

    @Override
    public List<String> groupMembers(String groupId) {
        List<UserRef> members = get(uri(b -> b.pathSegment("groups", groupId, "members")), USERS);
        return members == null ? List.of() : members.stream().filter(UserRef::enabled).map(UserRef::id).toList();
    }

    @Override
    public List<String> roleMembers(String role) {
        List<UserRef> members = get(uri(b -> b.pathSegment("roles", role, "members")), USERS);
        return members == null ? List.of() : members.stream().filter(UserRef::enabled).map(UserRef::id).toList();
    }

    @Override
    public Map<String, Object> profile(String userId) {
        UserDetail user = user(userId);
        Map<String, Object> profile = new LinkedHashMap<>();
        if (user != null) {
            profile.put("id", user.id());
            profile.put("username", user.username());
            profile.put("displayName", user.displayName());
            profile.put("email", user.email());
            profile.put("department", user.department());
            profile.put("position", user.position());
            profile.put("managerId", user.managerId());
        }
        return profile;
    }

    @Override
    public boolean isActiveUser(String userId) {
        UserDetail user = user(userId);
        return user != null && user.enabled();
    }

    @Override
    public List<String> groupIdsOf(String userId) {
        UserDetail user = user(userId);
        return user == null || user.groups() == null ? List.of() : user.groups().stream().map(GroupRef::id).toList();
    }

    private UserDetail user(String userId) {
        return get(uri(b -> b.pathSegment("users", userId)), new ParameterizedTypeReference<UserDetail>() { });
    }

    /** Builds an encoded URI; every path segment is encoded on its own, so '/' inside an id stays inside it. */
    private URI uri(Consumer<UriComponentsBuilder> path) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUri(baseUrl);
        path.accept(builder);
        return builder.build().encode().toUri();
    }

    /** Null on 404; any other failure means identity-service is unavailable. */
    private <T> T get(URI uri, ParameterizedTypeReference<T> type) {
        try {
            return breaker.executeSupplier(() -> {
                try {
                    return http.get().uri(uri).headers(h -> h.setBearerAuth(tokens.get())).retrieve().body(type);
                } catch (HttpClientErrorException.NotFound e) {
                    return null; // a normal answer, not a failure of identity-service
                }
            });
        } catch (CallNotPermittedException e) {
            throw new DirectoryUnavailableException("identity-service circuit is open after repeated failures", e);
        } catch (RestClientException | IllegalStateException e) {
            throw new DirectoryUnavailableException("identity-service call failed: " + uri.getPath(), e);
        }
    }
}
