package lk.auradot.wms.notification.inbox;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.notification.push.LivePush;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/** A person's own notifications; nobody, administrators included, reads someone else's. */
@RestController
public class NotificationController {

    static final int MAX_SIZE = 100;

    private final NotificationStore store;
    private final LivePush livePush;
    private final Clock clock;

    public NotificationController(NotificationStore store, LivePush livePush, Clock clock) {
        this.store = store;
        this.livePush = livePush;
        this.clock = clock;
    }

    @GetMapping("/notifications")
    public NotificationPage notifications(@RequestParam(defaultValue = "false") boolean unread,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @AuthenticationPrincipal Jwt jwt) {
        return store.page(jwt.getSubject(), unread, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", store.unreadCount(jwt.getSubject()));
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        if (!store.markRead(jwt.getSubject(), id, clock.instant())) {
            throw ApiException.notFound("NOTIF_NOT_FOUND", "No such notification");
        }
        announce(jwt.getSubject());
    }

    @PostMapping("/notifications/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal Jwt jwt) {
        store.markAllRead(jwt.getSubject(), clock.instant());
        announce(jwt.getSubject());
    }

    /** The person's other tabs and devices follow. Reading has already happened; a push that fails changes nothing. */
    private void announce(String userId) {
        try {
            livePush.unreadChanged(userId);
        } catch (RuntimeException e) {
            // best effort
        }
    }
}
