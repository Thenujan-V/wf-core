package lk.auradot.wms.notification.inbox;

import java.util.List;

/** {@code unread} counts all of the person's unread notifications, whatever the page or filter. */
public record NotificationPage(List<NotificationView> items, long total, long unread, int page, int size) {
}
