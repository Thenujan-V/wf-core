package lk.auradot.wms.identity.sync;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.IntFunction;

/** Read-only client for the Keycloak Admin REST API, authenticated as the sync service account. */
public class KeycloakAdminClient {

    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);
    private static final ParameterizedTypeReference<List<KcUser>> USERS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<KcGroup>> GROUPS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<IdOnly>> IDS = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<List<RoleName>> ROLES = new ParameterizedTypeReference<>() { };

    private final RestClient http;
    private final KeycloakAdminProperties props;
    private final Clock clock;
    private String accessToken;
    private Instant expiresAt = Instant.EPOCH;

    public KeycloakAdminClient(RestClient.Builder builder, KeycloakAdminProperties props, Clock clock) {
        this.http = builder.baseUrl(props.baseUrl().toString()).build();
        this.props = props;
        this.clock = clock;
    }

    public List<KcUser> listUsers() {
        return fetchAll(first -> getList("/admin/realms/{realm}/users?first={first}&max={max}&briefRepresentation=false",
                USERS, props.realm(), first, props.pageSize()));
    }

    public List<KcGroup> listGroups() {
        List<KcGroup> result = new ArrayList<>();
        Deque<KcGroup> pending = new ArrayDeque<>(fetchAll(first -> getList(
                "/admin/realms/{realm}/groups?first={first}&max={max}&briefRepresentation=false",
                GROUPS, props.realm(), first, props.pageSize())));
        while (!pending.isEmpty()) {
            KcGroup group = pending.poll();
            result.add(group);
            if (group.subGroupCount() != null && group.subGroupCount() > 0) {
                pending.addAll(fetchAll(first -> getList(
                        "/admin/realms/{realm}/groups/{id}/children?first={first}&max={max}&briefRepresentation=false",
                        GROUPS, props.realm(), group.id(), first, props.pageSize())));
            }
        }
        return result;
    }

    public List<String> groupMemberIds(String groupId) {
        return fetchAll(first -> getList(
                "/admin/realms/{realm}/groups/{id}/members?first={first}&max={max}&briefRepresentation=true",
                IDS, props.realm(), groupId, first, props.pageSize()))
                .stream().map(IdOnly::id).toList();
    }

    public List<String> effectiveRealmRoles(String userId) {
        return getList("/admin/realms/{realm}/users/{id}/role-mappings/realm/composite", ROLES, props.realm(), userId)
                .stream().map(RoleName::name).toList();
    }

    private <T> List<T> fetchAll(IntFunction<List<T>> page) {
        List<T> all = new ArrayList<>();
        int first = 0;
        while (true) {
            List<T> batch = page.apply(first);
            all.addAll(batch);
            if (batch.size() < props.pageSize()) {
                return all;
            }
            first += props.pageSize();
        }
    }

    private <T> List<T> getList(String uri, ParameterizedTypeReference<List<T>> type, Object... uriVariables) {
        List<T> body = http.get()
                .uri(uri, uriVariables)
                .headers(h -> h.setBearerAuth(token()))
                .retrieve()
                .body(type);
        return body == null ? List.of() : body;
    }

    private synchronized String token() {
        Instant now = clock.instant();
        if (accessToken != null && now.isBefore(expiresAt.minus(EXPIRY_SKEW))) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        TokenResponse response = http.post()
                .uri("/realms/{realm}/protocol/openid-connect/token", props.realm())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Keycloak returned no access token for the sync client");
        }
        accessToken = response.accessToken();
        expiresAt = now.plusSeconds(response.expiresIn());
        return accessToken;
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken,
                         @JsonProperty("expires_in") long expiresIn) {
    }

    record IdOnly(String id) {
    }

    record RoleName(String name) {
    }
}
