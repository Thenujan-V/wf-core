package lk.auradot.wms.notification;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class TestDb {

    private TestDb() {
    }

    public static void clear(JdbcClient jdbc) {
        for (String table : new String[] {"notification", "notification_preference", "email_message", "processed_message"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }
}
