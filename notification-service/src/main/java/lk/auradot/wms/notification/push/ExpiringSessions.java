package lk.auradot.wms.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A connection is only checked when it is opened, so it must not outlive the token that opened it (someone who
 * signed out, or whose account was disabled, would keep receiving). The app reconnects with a fresh token.
 * The handshake is open to anyone, so a connection that does not sign in within a moment is closed too.
 */
@Component
public class ExpiringSessions implements WebSocketHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger(ExpiringSessions.class);

    /** How long a connection may take to send its CONNECT frame with a token. */
    static final Duration SIGN_IN_GRACE = Duration.ofSeconds(15);

    private record Open(WebSocketSession session, Instant since) {
    }

    private final Map<String, Open> open = new ConcurrentHashMap<>();
    private final Clock clock;

    public ExpiringSessions(Clock clock) {
        this.clock = clock;
    }

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                open.put(session.getId(), new Open(session, clock.instant()));
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                open.remove(session.getId());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    /** Closes every connection whose token has run out, or that never signed in; returns how many (for tests). */
    @Scheduled(fixedDelayString = "PT15S")
    public int closeExpired() {
        Instant now = clock.instant();
        int closed = 0;
        for (Open connection : open.values()) {
            WebSocketSession session = connection.session();
            boolean signedIn = session.getAttributes().get(StompAuthInterceptor.EXPIRES_AT) instanceof Instant;
            Instant until = signedIn ? (Instant) session.getAttributes().get(StompAuthInterceptor.EXPIRES_AT)
                    : connection.since().plus(SIGN_IN_GRACE);
            if (!until.isAfter(now)) {
                try {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason(signedIn ? "token expired" : "not signed in"));
                } catch (IOException e) {
                    log.debug("Closing a connection failed: {}", e.getMessage());
                }
                closed++;
            }
        }
        return closed;
    }
}
