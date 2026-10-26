package lk.auradot.wms.notification.inbox;

import java.time.Instant;

public record NotificationView(String id, String kind, String title, String body, String link, Instant createdAt,
                               Instant readAt) {
}
