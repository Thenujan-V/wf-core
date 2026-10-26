package lk.auradot.wms.notification.push;

import lk.auradot.wms.notification.inbox.NotificationView;

/**
 * Tells a person's open app at once (spec §8: "pushed via STOMP over WebSocket"). Best effort: what counts is what is
 * stored; the app asks for it again whenever it reconnects.
 */
public interface LivePush {

    /** A notification was stored for this person. */
    void created(String userId, NotificationView notification);

    /** The person read something: their other tabs and devices should show the new count. */
    void unreadChanged(String userId);
}
