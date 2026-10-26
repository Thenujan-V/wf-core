package lk.auradot.wms.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;

/** Turns routing failures (no route, service down, timeouts) into ProblemDetail responses. */
@Component
@Order(-2) // before Boot's DefaultErrorWebExceptionHandler (-1)
public class GatewayErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = statusOf(ex);
        if (status.is5xxServerError()) {
            log.warn("Gateway error {} for {} [{}]", status.value(), exchange.getRequest().getPath(),
                    CorrelationIdWebFilter.of(exchange), ex);
        }
        return ProblemResponses.write(exchange, status, status.name(), detailFor(status));
    }

    private static HttpStatus statusOf(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            // Refused/timed-out connections and unresolvable service hosts both mean "service not reachable".
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus status = HttpStatus.resolve(rse.getStatusCode().value());
            if (status != null) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String detailFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "Resource not found";
            case SERVICE_UNAVAILABLE, BAD_GATEWAY -> "The service is temporarily unavailable. Try again shortly.";
            case GATEWAY_TIMEOUT -> "The service took too long to respond. Try again shortly.";
            case INTERNAL_SERVER_ERROR -> "Unexpected error";
            default -> status.getReasonPhrase();
        };
    }
}
