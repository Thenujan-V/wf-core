package lk.auradot.wms.form;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class TestDb {

    private TestDb() {
    }

    public static void clear(JdbcClient jdbc) {
        for (String table : new String[] {"form_submission", "form_definition", "form_draft"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }
}
