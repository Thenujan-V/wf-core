package lk.auradot.wms.gateway;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Writes RFC 7807 bodies for errors the gateway itself produces, in the same shape the services use
 * (status, title, detail, code, correlationId). Every value is a constant or a validated correlation id,
 * so the JSON is assembled directly.
 */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String correlationId = CorrelationIdWebFilter.of(exchange);
        String body = "{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\""
                + (correlationId != null ? ",\"correlationId\":\"" + correlationId + "\"" : "") + "}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
