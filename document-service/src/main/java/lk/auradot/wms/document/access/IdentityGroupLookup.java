package lk.auradot.wms.document.access;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Reads the caller's groups from identity-service {@code /me} with the caller's own token, so identity-service also
 * syncs an unknown user just in time. Results are cached briefly per user.
 */
public class IdentityGroupLookup implements GroupLookup {

    private record Cached(Set<String> groupIds, Instant expiresAt) {
    }

    record Me(List<Group> groups) {
    }

    record Group(String id) {
    }

    private final RestClient http;
    private final IdentityClientProperties props;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public IdentityGroupLookup(RestClient.Builder builder, IdentityClientProperties props, Clock clock) {
        this.http = builder.baseUrl(props.baseUrl().toString()).build();
        this.props = props;
        this.clock = clock;
    }

    @Override
    public Set<String> groupIds(String userId, String token) {
        Instant now = clock.instant();
        Cached cached = cache.get(userId);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.groupIds();
        }
        Me me;
        try {
            me = http.get().uri("/me").header("Authorization", "Bearer " + token).retrieve().body(Me.class);
        } catch (HttpClientErrorException.NotFound e) {
            // Service accounts (and anyone identity-service does not keep) belong to no group; that is not an outage.
            me = null;
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOC_IDENTITY_UNAVAILABLE",
                    "Your group memberships could not be checked; please try again shortly");
        }
        Set<String> ids = me == null || me.groups() == null ? Set.of()
                : me.groups().stream().map(Group::id).collect(Collectors.toUnmodifiableSet());
        cache.put(userId, new Cached(ids, now.plus(props.cacheTtl())));
        return ids;
    }
}
