package lk.auradot.wms.audit;

import org.springframework.jdbc.core.simple.JdbcClient;

/** A connection with every privilege, for test set-up. The service itself may only add and read entries. */
public class AuditAdminDb {

    private final JdbcClient jdbc;

    public AuditAdminDb(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    public void clear() {
        jdbc.sql("DELETE FROM audit_entry").update();
    }
}
