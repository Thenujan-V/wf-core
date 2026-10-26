package lk.auradot.wms.document.approval;

import lk.auradot.wms.common.security.ServiceTokens;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;

/**
 * {@link People} over identity-service's REST API, as the wms-document service account.
 * <p>
 * A name is printed in an approved PDF that is never made again, so a person is shown by their id only when
 * identity-service itself says it knows no such person (a 404 with its own error code), or knows them without a name.
 * Anything else is "try again later".
 */
public class HttpPeople implements People {

    private static final String NO_SUCH_PERSON = "\"IDN_USER_NOT_FOUND\"";

    record Person(String id, String displayName) {
    }

    private final RestClient http;
    private final URI baseUrl;
    private final ServiceTokens tokens;

    public HttpPeople(RestClient.Builder builder, URI baseUrl, ServiceAccountProperties account, Clock clock) {
        this.http = builder.build();
        this.baseUrl = baseUrl;
        this.tokens = new ServiceTokens(http, account.tokenUrl(), account.clientId(), account.clientSecret(), clock);
    }

    @Override
    public String displayName(String userId) {
        String token;
        try {
            token = tokens.get();
        } catch (RuntimeException e) {
            throw unavailable(userId, "document-service could not sign in as its service account: " + e.getMessage(), e);
        }
        URI uri = UriComponentsBuilder.fromUri(baseUrl).pathSegment("users", userId).build().encode().toUri();
        Person person;
        try {
            person = http.get().uri(uri).headers(h -> h.setBearerAuth(token)).retrieve().body(Person.class);
        } catch (HttpClientErrorException.NotFound e) {
            if (e.getResponseBodyAsString().contains(NO_SUCH_PERSON)) {
                return userId;
            }
            throw unavailable(userId, "a 404 that is not identity-service's \"no such person\"", e);
        } catch (RestClientException | IllegalStateException e) {
            throw unavailable(userId, e.getMessage(), e);
        }
        if (person == null) {
            throw unavailable(userId, "an empty answer", null);
        }
        return person.displayName() == null || person.displayName().isBlank() ? userId : person.displayName().strip();
    }

    private static ApprovalUnavailableException unavailable(String userId, String why, Throwable cause) {
        return new ApprovalUnavailableException("identity-service could not name " + userId + ": " + why, cause);
    }
}
