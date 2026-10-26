package lk.auradot.wms.notification.push;

import lk.auradot.wms.notification.MutableClock;
import lk.auradot.wms.notification.TestDb;
import lk.auradot.wms.notification.TestNotificationConfiguration;
import lk.auradot.wms.notification.TestRabbit;
import lk.auradot.wms.notification.TestTokens;
import lk.auradot.wms.notification.TestcontainersConfiguration;
import lk.auradot.wms.notification.inbox.NotificationStore;
import lk.auradot.wms.notification.inbox.NotificationView;
import lk.auradot.wms.notification.plan.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A real WebSocket client against the running service and a real broker (spec §8 live push). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wms.notification.email.dispatch-enabled=false")
@Import({TestcontainersConfiguration.class, TestNotificationConfiguration.class, TestRabbit.class})
class LivePushSocketTest {

    static final String APP = "http://localhost:4200";

    @LocalServerPort int port;
    @Autowired BrokerLivePush livePush;
    @Autowired NotificationStore store;
    @Autowired ExpiringSessions sessions;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    WebSocketStompClient client;
    final List<StompSession> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock.set(MutableClock.DEFAULT);
        TestDb.clear(jdbc);
        client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new StringMessageConverter());
    }

    @AfterEach
    void tearDown() {
        for (StompSession session : opened) {
            if (session.isConnected()) {
                session.disconnect();
            }
        }
        client.stop();
        clock.set(MutableClock.DEFAULT);
    }

    StompSession connect(String token, String origin) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        if (origin != null) {
            handshake.setOrigin(origin);
        }
        StompHeaders connect = new StompHeaders();
        if (token != null) {
            connect.add("Authorization", "Bearer " + token);
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", handshake, connect,
                new StompSessionHandlerAdapter() {
                }).get(10, TimeUnit.SECONDS);
        opened.add(session);
        return session;
    }

    BlockingQueue<String> listen(StompSession session, String destination) {
        BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                frames.add((String) payload);
            }
        });
        return frames;
    }

    /** A subscription takes a moment to take effect; this pings until the first frame comes through, then clears. */
    void ready(BlockingQueue<String> frames, String userId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            livePush.unreadChanged(userId);
            if (frames.poll(300, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(700);
                frames.clear();
                return;
            }
        }
        throw new AssertionError("no frame reached " + userId + " within 15 seconds");
    }

    boolean closedWithin(StompSession session, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (!session.isConnected()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPersonGetsTheirOwnNotificationsLiveAndNobodyElses() throws Exception {
        BlockingQueue<String> bob = listen(connect(TestTokens.of("bob"), APP), "/user/queue/notifications");
        BlockingQueue<String> carol = listen(connect(TestTokens.of("carol"), APP), "/user/queue/notifications");
        ready(bob, "bob");
        ready(carol, "carol");
        NotificationView stored = store.insert("bob", Kind.TASK_ASSIGNED, "New task: Review", "You have a new task.", "/inbox", clock.instant());

        livePush.created("bob", stored);

        String frame = bob.poll(10, TimeUnit.SECONDS);
        assertThat(frame).as("bob's frame").isNotNull();
        Map<String, Object> parsed = json.readValue(frame, Map.class);
        assertThat(parsed).containsEntry("type", "created").containsEntry("unread", 1).doesNotContainKey("userId");
        assertThat((Map<String, Object>) parsed.get("notification")).containsEntry("id", stored.id())
                .containsEntry("kind", "TASK_ASSIGNED").containsEntry("title", "New task: Review")
                .containsEntry("link", "/inbox").containsEntry("createdAt", "2026-10-01T09:00:00Z");
        assertThat(carol.poll(1, TimeUnit.SECONDS)).as("carol must not see bob's notification").isNull();
    }

    @Test
    void aConnectionWithoutAnAcceptedTokenIsRefused() {
        assertThatThrownBy(() -> connect(null, APP)).isInstanceOf(java.util.concurrent.ExecutionException.class);
        assertThatThrownBy(() -> connect("not-a-token", APP)).isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void aPageFromAnotherSiteCannotConnect() {
        assertThatThrownBy(() -> connect(TestTokens.of("bob"), "https://evil.example"))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void onlyOnesOwnQueueCanBeSubscribedToAndNothingCanBeSent() throws Exception {
        StompSession nosy = connect(TestTokens.of("bob"), APP);
        listen(nosy, "/queue/notifications");
        assertThat(closedWithin(nosy, 5)).as("subscribing to the shared queue closes the connection").isTrue();

        StompSession talker = connect(TestTokens.of("bob"), APP);
        talker.send("/user/carol/queue/notifications", "{\"type\":\"created\"}");
        assertThat(closedWithin(talker, 5)).as("sending closes the connection").isTrue();
    }

    @Test
    void aConnectionIsClosedWhenItsTokenRunsOut() throws Exception {
        StompSession bob = connect(TestTokens.of("bob"), APP);
        sessions.closeExpired();
        assertThat(closedWithin(bob, 1)).as("the token is still good").isFalse();

        clock.advance(TestTokens.LIFETIME.plus(Duration.ofSeconds(1)));
        sessions.closeExpired();

        assertThat(closedWithin(bob, 5)).isTrue();
    }

    /** A bare WebSocket, for what a well-behaved STOMP client would never send. */
    static class Raw extends TextWebSocketHandler {

        final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        volatile boolean closed;

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed = true;
        }

        boolean closedWithin(int seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (System.nanoTime() < deadline) {
                if (closed) {
                    return true;
                }
                Thread.sleep(100);
            }
            return false;
        }
    }

    WebSocketSession raw(Raw handler) throws Exception {
        WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
        handshake.setOrigin(APP);
        return new StandardWebSocketClient().execute(handler, handshake, URI.create("ws://localhost:" + port + "/ws"))
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void aFrameAddressedToSomeoneElseIsRefusedAndNeverDelivered() throws Exception {
        BlockingQueue<String> bob = listen(connect(TestTokens.of("bob"), APP), "/user/queue/notifications");
        ready(bob, "bob");
        Raw carol = new Raw();
        WebSocketSession socket = raw(carol);
        socket.sendMessage(new TextMessage("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer "
                + TestTokens.of("carol") + "\n\n\u0000"));
        assertThat(carol.received.poll(5, TimeUnit.SECONDS)).as("carol signs in").startsWith("CONNECTED");

        // Not SEND: a MESSAGE frame, which only a server should ever send.
        socket.sendMessage(new TextMessage("MESSAGE\ndestination:/user/bob/queue/notifications\nmessage-id:m-1\n"
                + "subscription:sub-0\ncontent-type:text/plain\n\n{\"type\":\"unread\",\"notification\":null,\"unread\":0}\u0000"));

        assertThat(bob.poll(2, TimeUnit.SECONDS)).as("bob must not receive what carol sent").isNull();
        assertThat(carol.closedWithin(5)).as("the connection that tried is closed").isTrue();
    }

    @Test
    void aConnectionThatNeverSignsInIsClosed() throws Exception {
        Raw silent = new Raw();
        raw(silent);
        sessions.closeExpired();
        assertThat(silent.closedWithin(1)).as("it has a moment to sign in").isFalse();

        clock.advance(Duration.ofSeconds(16));
        sessions.closeExpired();

        assertThat(silent.closedWithin(5)).isTrue();
    }
}
