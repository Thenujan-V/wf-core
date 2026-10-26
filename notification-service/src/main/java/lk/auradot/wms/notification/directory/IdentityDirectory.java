package lk.auradot.wms.notification.directory;

import lk.auradot.wms.common.security.ServiceTokens;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** {@link Directory} over identity-service's REST API, as the wms-notification service account. */
public class IdentityDirectory implements Directory {

    private static final ParameterizedTypeReference<List<Person>> PEOPLE = new ParameterizedTypeReference<>() {
    };

    private final RestClient http;
    private final URI baseUrl;
    private final ServiceTokens tokens;

    public IdentityDirectory(RestClient.Builder builder, IdentityClientProperties props, Clock clock) {
        this.http = builder.build();
        this.baseUrl = props.baseUrl();
        this.tokens = new ServiceTokens(http, props.tokenUrl(), props.clientId(), props.clientSecret(), clock);
    }

    @Override
    public Optional<Person> person(String userId) {
        URI uri = uri("users", userId);
        try {
            return Optional.ofNullable(http.get().uri(uri).headers(h -> h.setBearerAuth(tokens.get())).retrieve().body(Person.class));
        } catch (HttpClientErrorException e) {
            if (answered(e)) {
                return Optional.empty();
            }
            throw new DirectoryUnavailableException("identity-service call failed: " + uri.getPath(), e);
        } catch (RestClientException | IllegalStateException e) {
            throw new DirectoryUnavailableException("identity-service call failed: " + uri.getPath(), e);
        }
    }

    @Override
    public List<Person> groupMembers(String groupId) {
        return members(uri("groups", groupId, "members"));
    }

    @Override
    public List<Person> roleMembers(String role) {
        return members(uri("roles", role, "members"));
    }

    private List<Person> members(URI uri) {
        try {
            List<Person> people = http.get().uri(uri).headers(h -> h.setBearerAuth(tokens.get())).retrieve().body(PEOPLE);
            return people == null ? List.of() : people;
        } catch (HttpClientErrorException e) {
            if (answered(e)) {
                return List.of(); // a group or role that no longer exists has no members
            }
            throw new DirectoryUnavailableException("identity-service call failed: " + uri.getPath(), e);
        } catch (RestClientException | IllegalStateException e) {
            throw new DirectoryUnavailableException("identity-service call failed: " + uri.getPath(), e);
        }
    }

    /**
     * A 4xx is identity-service answering "there is no such person (or group)", also for an id it finds malformed:
     * asking again would get the same answer, so it must not be retried for ever. Being refused entry or asked to
     * slow down is not an answer: that is treated as an outage and passes.
     */
    private static boolean answered(HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        return status != 401 && status != 403 && status != 408 && status != 429;
    }

    /** Every path segment is encoded on its own, so '/' inside an id stays inside it. */
    private URI uri(String... segments) {
        return UriComponentsBuilder.fromUri(baseUrl).pathSegment(segments).build().encode().toUri();
    }
}
