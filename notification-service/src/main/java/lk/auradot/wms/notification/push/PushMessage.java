package lk.auradot.wms.notification.push;

import lk.auradot.wms.notification.inbox.NotificationView;

/** What travels between instances: whom to tell, and what. {@code notification} is null for {@link #UNREAD}. */
public record PushMessage(String userId, String type, NotificationView notification, long unread) {

    public static final String CREATED = "created";
    public static final String UNREAD = "unread";
}
