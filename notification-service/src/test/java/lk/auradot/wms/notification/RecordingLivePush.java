package lk.auradot.wms.notification;

import lk.auradot.wms.notification.inbox.NotificationView;
import lk.auradot.wms.notification.push.LivePush;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for the live connection to people's browsers: keeps what it was asked to tell them. */
public class RecordingLivePush implements LivePush {

    public record Pushed(String userId, NotificationView notification) {
    }

    private final List<Pushed> pushed = new CopyOnWriteArrayList<>();
    private final List<String> countChanges = new CopyOnWriteArrayList<>();
    private volatile boolean failing;

    public void reset() {
        pushed.clear();
        countChanges.clear();
        failing = false;
    }

    /** Live push is broken: every call throws. */
    public void failing(boolean failing) {
        this.failing = failing;
    }

    public List<Pushed> pushed() {
        return pushed;
    }

    /** The people whose unread count was announced, in order. */
    public List<String> countChanges() {
        return countChanges;
    }

    @Override
    public void created(String userId, NotificationView notification) {
        check();
        pushed.add(new Pushed(userId, notification));
    }

    @Override
    public void unreadChanged(String userId) {
        check();
        countChanges.add(userId);
    }

    private void check() {
        if (failing) {
            throw new IllegalStateException("live push is broken (test)");
        }
    }
}
