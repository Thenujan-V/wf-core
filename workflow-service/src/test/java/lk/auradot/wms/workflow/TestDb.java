package lk.auradot.wms.workflow;

import org.springframework.jdbc.core.simple.JdbcClient;

public final class TestDb {

    private TestDb() {
    }

    public static void clear(JdbcClient jdbc) {
        for (String table : new String[] {"outbox", "history_event", "task_candidate", "task", "job", "fork_scope",
                "execution_token", "process_instance", "process_definition", "process_draft"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
    }
}
