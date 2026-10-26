package lk.auradot.wms.common.error;

import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A business error with a stable, machine-readable code; rendered as an RFC 7807 ProblemDetail. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private final Map<String, Object> properties = new LinkedHashMap<>();

    /** Adds a ProblemDetail property, e.g. the list of validation errors. */
    public ApiException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public Map<String, Object> properties() {
        return Collections.unmodifiableMap(properties);
    }

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
