package lk.auradot.wms.notification.push;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Who may connect, and what a connection may do. A browser cannot put a token on the WebSocket handshake, so the
 * token comes in the STOMP CONNECT frame and is checked exactly like one on a REST call. A connection may subscribe
 * to its own person's queue and nothing else; every frame that is not named below is refused. Anything refused
 * here closes the connection.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    /** Session attribute: when the token that opened the connection runs out. */
    static final String EXPIRES_AT = "wms.tokenExpiresAt";
    private static final String BEARER = "Bearer ";

    private final JwtDecoder decoder;

    public StompAuthInterceptor(JwtDecoder decoder) {
        this.decoder = decoder;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeats
        }
        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> connect(accessor);
            case SUBSCRIBE -> {
                if (accessor.getUser() == null || !PushSocketConfig.USER_DESTINATION.equals(accessor.getDestination())) {
                    throw new AccessDeniedException("Only your own notifications can be subscribed to");
                }
            }
            case UNSUBSCRIBE, DISCONNECT, ACK, NACK -> {
                // need no permission
            }
            // Everything else is refused, not only SEND: a MESSAGE frame from a client would otherwise be delivered
            // to whoever it names.
            default -> throw new AccessDeniedException("This connection only delivers notifications");
        }
        return message;
    }

    private void connect(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            throw new AccessDeniedException("A bearer token is required");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(header.substring(BEARER.length()).strip());
        } catch (JwtException e) {
            throw new AccessDeniedException("The token was not accepted");
        }
        if (jwt.getSubject() == null || jwt.getExpiresAt() == null) {
            throw new AccessDeniedException("The token was not accepted");
        }
        // The name is the person's id: it is what notifications are addressed to.
        accessor.setUser(new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject()));
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes != null) {
            attributes.put(EXPIRES_AT, jwt.getExpiresAt());
        }
    }
}
