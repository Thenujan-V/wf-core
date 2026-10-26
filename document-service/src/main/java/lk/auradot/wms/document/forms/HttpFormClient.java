package lk.auradot.wms.document.forms;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HttpFormClient implements FormClient {

    record ValidateResponse(boolean valid, List<Map<String, Object>> errors, Map<String, Object> data) {
    }

    record StoredForm(String status) {
    }

    private final RestClient http;

    public HttpFormClient(RestClient.Builder builder, FormClientProperties props) {
        this.http = builder.baseUrl(props.baseUrl().toString()).build();
    }

    @Override
    public Map<String, Object> validate(FormRef form, Map<String, Object> data, String token) {
        ValidateResponse response;
        try {
            response = http.post().uri("/validate").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("formKey", form.key(), "version", form.version(), "data", data))
                    .retrieve().body(ValidateResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.conflict("DOC_TYPE_FORM_MISSING", "The form of this document type is no longer available");
        } catch (RestClientException e) {
            throw unavailable();
        }
        if (response == null) {
            throw unavailable();
        }
        if (!response.valid()) {
            throw FormClient.invalid(response.errors() == null ? List.of() : response.errors());
        }
        return response.data() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.data());
    }

    @Override
    public boolean isPublished(FormRef form, String token) {
        try {
            StoredForm stored = http.get().uri("/definitions/{key}/versions/{version}", form.key(), form.version())
                    .header("Authorization", "Bearer " + token).retrieve().body(StoredForm.class);
            return stored != null && "PUBLISHED".equals(stored.status());
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOC_FORM_SERVICE_UNAVAILABLE",
                "Forms are unavailable right now; please try again shortly");
    }
}
