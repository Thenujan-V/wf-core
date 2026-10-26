package lk.auradot.wms.notification.inbox;

import lk.auradot.wms.notification.plan.Kind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** A person's in-app notifications. All DATETIME columns hold UTC. */
@Repository
public class NotificationStore {

    static final int TITLE_MAX = 255;
    static final int BODY_MAX = 1000;

    private final JdbcClient jdbc;

    public NotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns what was stored (long texts are cut, not refused). */
    public NotificationView insert(String userId, Kind kind, String title, String body, String link, Instant now) {
        String id = UUID.randomUUID().toString();
        String storedTitle = cut(title, TITLE_MAX);
        String storedBody = cut(body, BODY_MAX);
        jdbc.sql("""
                        INSERT INTO notification (id, user_id, kind, title, body, link, created_at)
                        VALUES (:id, :user, :kind, :title, :body, :link, :at)""")
                .param("id", id).param("user", userId).param("kind", kind.name()).param("title", storedTitle)
                .param("body", storedBody).param("link", link).param("at", utc(now)).update();
        return new NotificationView(id, kind.name(), storedTitle, storedBody, link, now, null);
    }

    /** A customer's own template may run long; the text is cut, not refused. */
    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** The person's notifications, newest first. */
    public NotificationPage page(String userId, boolean unreadOnly, int page, int size) {
        String filter = unreadOnly ? " AND read_at IS NULL" : "";
        long total = jdbc.sql("SELECT COUNT(*) FROM notification WHERE user_id = :user" + filter).param("user", userId)
                .query(Long.class).single();
        List<NotificationView> items = jdbc.sql("""
                        SELECT id, kind, title, body, link, created_at, read_at FROM notification
                        WHERE user_id = :user""" + filter + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                .param("user", userId).param("limit", size).param("offset", (long) page * size)
                .query(NotificationStore::map).list();
        return new NotificationPage(items, total, unreadCount(userId), page, size);
    }

    public long unreadCount(String userId) {
        return jdbc.sql("SELECT COUNT(*) FROM notification WHERE user_id = :user AND read_at IS NULL").param("user", userId)
                .query(Long.class).single();
    }

    /** False when the person has no such notification. Reading one twice keeps the first time. */
    public boolean markRead(String userId, String id, Instant now) {
        jdbc.sql("UPDATE notification SET read_at = :at WHERE id = :id AND user_id = :user AND read_at IS NULL")
                .param("at", utc(now)).param("id", id).param("user", userId).update();
        return jdbc.sql("SELECT COUNT(*) FROM notification WHERE id = :id AND user_id = :user")
                .param("id", id).param("user", userId).query(Long.class).single() > 0;
    }

    public void markAllRead(String userId, Instant now) {
        jdbc.sql("UPDATE notification SET read_at = :at WHERE user_id = :user AND read_at IS NULL")
                .param("at", utc(now)).param("user", userId).update();
    }

    private static NotificationView map(ResultSet rs, int row) throws SQLException {
        LocalDateTime readAt = rs.getObject("read_at", LocalDateTime.class);
        return new NotificationView(rs.getString("id"), rs.getString("kind"), rs.getString("title"), rs.getString("body"),
                rs.getString("link"), rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                readAt == null ? null : readAt.toInstant(ZoneOffset.UTC));
    }
}
