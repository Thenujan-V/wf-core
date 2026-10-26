package lk.auradot.wms.common.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Client-credentials access token for calling other WMS services, refreshed 30 s before it expires. */
public final class ServiceTokens {

    private static final Duration SKEW = Duration.ofSeconds(30);

    private final RestClient http;
    private final URI tokenUrl;
    private final String clientId;
    private final String clientSecret;
    private final Clock clock;
    private String token;
    private Instant expiresAt = Instant.EPOCH;

    public ServiceTokens(RestClient http, URI tokenUrl, String clientId, String clientSecret, Clock clock) {
        this.http = http;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clock = clock;
    }

    public synchronized String get() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(expiresAt.minus(SKEW))) {
            return token;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        TokenResponse response = http.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(TokenResponse.class);
        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Keycloak returned no service token");
        }
        token = response.accessToken();
        expiresAt = now.plusSeconds(response.expiresIn());
        return token;
    }

    record TokenResponse(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {
    }
}
