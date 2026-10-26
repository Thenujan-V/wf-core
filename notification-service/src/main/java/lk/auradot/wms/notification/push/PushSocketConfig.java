package lk.auradot.wms.notification.push;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/** Spec §8: notifications are pushed via STOMP over WebSocket (through the gateway at /api/notifications/ws). */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class PushSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";
    /** What a client subscribes to: its own person's notifications. */
    public static final String USER_DESTINATION = "/user/queue/notifications";
    /** The same queue as the server addresses it (with the person's id). */
    static final String QUEUE = "/queue/notifications";

    static final long HEARTBEAT_MS = 10_000;
    /** Frames from a client are tiny (CONNECT with a token, SUBSCRIBE). */
    static final int MESSAGE_SIZE_LIMIT = 16 * 1024;
    /**
     * A connection that says nothing after the handshake is dropped. Spring only checks this when another
     * connection arrives, so ExpiringSessions also closes connections that have not signed in.
     */
    static final int TIME_TO_FIRST_MESSAGE_MS = 15_000;

    private final PushProperties props;
    private final StompAuthInterceptor auth;
    private final ExpiringSessions sessions;
    private final TaskScheduler scheduler;

    public PushSocketConfig(PushProperties props, StompAuthInterceptor auth, ExpiringSessions sessions,
                            @Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler scheduler) {
        this.props = props;
        this.auth = auth;
        this.sessions = sessions;
        this.scheduler = scheduler;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT).setAllowedOrigins(props.allowedOrigins().toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/queue").setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS}).setTaskScheduler(scheduler);
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(auth);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry.setMessageSizeLimit(MESSAGE_SIZE_LIMIT).setTimeToFirstMessage(TIME_TO_FIRST_MESSAGE_MS).addDecoratorFactory(sessions);
    }
}
