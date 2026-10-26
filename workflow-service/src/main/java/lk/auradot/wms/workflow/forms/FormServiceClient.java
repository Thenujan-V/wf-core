package lk.auradot.wms.workflow.forms;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lk.auradot.wms.workflow.directory.IdentityClientProperties;
import lk.auradot.wms.common.security.ServiceTokens;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** {@link FormClient} over form-service's REST API, as the wms-workflow service account. */
public class FormServiceClient implements FormClient {

    record StoredVersion(String key, int version, String status, Map<String, Object> definition) {
    }

    record StoredSubmission(String id, Map<String, Object> data) {
    }

    private final RestClient http;
    private final URI baseUrl;
    private final ServiceTokens tokens;
    /** Validation failures are normal answers, not a sign that form-service is unhealthy. */
    private final CircuitBreaker breaker = CircuitBreaker.of("form-service", CircuitBreakerConfig.custom()
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .ignoreExceptions(FormValidationException.class, FormFilesUnavailableException.class, HttpClientErrorException.class)
            .build());

    public FormServiceClient(RestClient.Builder builder, FormClientProperties props, IdentityClientProperties credentials,
                             Clock clock) {
        this.http = builder.build();
        this.baseUrl = props.baseUrl();
        this.tokens = new ServiceTokens(http, credentials.tokenUrl(), credentials.clientId(), credentials.clientSecret(), clock);
    }

    @Override
    public Optional<FormVersion> version(String key, int version) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl).pathSegment("definitions", key, "versions", String.valueOf(version))
                .build().encode().toUri();
        return Optional.ofNullable(call(uri, () -> {
            try {
                StoredVersion stored = http.get().uri(uri).headers(h -> h.setBearerAuth(tokens.get()))
                        .retrieve().body(StoredVersion.class);
                return stored == null ? null
                        : new FormVersion(stored.key(), stored.version(), stored.status(), stored.definition());
            } catch (HttpClientErrorException.NotFound e) {
                return null;
            }
        }));
    }

    @Override
    public Submitted submit(SubmitForm request) {
        URI uri = UriComponentsBuilder.fromUri(baseUrl).path("/submissions").build().toUri();
        return call(uri, () -> {
            try {
                StoredSubmission stored = http.post().uri(uri).headers(h -> h.setBearerAuth(tokens.get()))
                        .contentType(MediaType.APPLICATION_JSON).body(request)
                        .retrieve().body(StoredSubmission.class);
                if (stored == null) {
                    throw new IllegalStateException("form-service returned no submission");
                }
                return new Submitted(stored.id(), stored.data());
            } catch (HttpClientErrorException.BadRequest e) {
                Map<?, ?> problem = e.getResponseBodyAs(Map.class);
                if (problem != null && "FORM_VALIDATION_FAILED".equals(problem.get("code"))
                        && problem.get("errors") instanceof List<?> errors) {
                    throw new FormValidationException(fieldErrors(errors));
                }
                throw e;
            } catch (HttpServerErrorException.ServiceUnavailable e) {
                Map<?, ?> problem = e.getResponseBodyAs(Map.class);
                if (problem != null && "FORM_FILES_UNAVAILABLE".equals(problem.get("code"))) {
                    throw new FormFilesUnavailableException("form-service could not check the files", e);
                }
                throw e;
            }
        });
    }

    private <T> T call(URI uri, Supplier<T> work) {
        try {
            return breaker.executeSupplier(work);
        } catch (FormValidationException e) {
            throw e;
        } catch (HttpClientErrorException e) {
            throw new FormServiceRejectedException("form-service refused " + uri.getPath() + ": " + e.getStatusCode(), e);
        } catch (CallNotPermittedException e) {
            throw new FormServiceUnavailableException("form-service circuit is open after repeated failures", e);
        } catch (RestClientException | IllegalStateException e) {
            throw new FormServiceUnavailableException("form-service call failed: " + uri.getPath(), e);
        }
    }

    private static List<Map<String, Object>> fieldErrors(List<?> errors) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object error : errors) {
            if (error instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                result.add(copy);
            }
        }
        return result;
    }
}
