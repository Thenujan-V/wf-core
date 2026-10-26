package lk.auradot.wms.workflow.monitor;

import lk.auradot.wms.workflow.Times;
import lk.auradot.wms.workflow.instance.InstanceSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Spec §9 admin/monitor: every request, whoever started it. */
@Repository
public class MonitorQueries {

    private static final String COLUMNS =
            "id, process_key, process_version, business_key, status, end_status, initiator_id, started_at, ended_at";

    private final JdbcClient jdbc;

    public MonitorQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Newest first. Each filter may be null; {@code q} is a request's id or the start of its reference. */
    public InstancePage instances(String status, String processKey, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (status != null) {
            where.append(" AND status = :status");
            params.put("status", status);
        }
        if (processKey != null) {
            where.append(" AND process_key = :processKey");
            params.put("processKey", processKey);
        }
        if (q != null) {
            where.append(" AND (id = :q OR business_key LIKE :prefix ESCAPE '!')");
            params.put("q", q);
            params.put("prefix", escapeLike(q) + "%");
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM process_instance" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<InstanceSummary> items = jdbc.sql("SELECT " + COLUMNS + " FROM process_instance" + where
                        + " ORDER BY started_at DESC, id LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, row) -> new InstanceSummary(rs.getString("id"), rs.getString("process_key"),
                        rs.getInt("process_version"), rs.getString("business_key"), rs.getString("status"),
                        rs.getString("end_status"), rs.getString("initiator_id"),
                        Times.fromDb(rs.getObject("started_at", LocalDateTime.class)),
                        Times.fromDb(rs.getObject("ended_at", LocalDateTime.class))))
                .list();
        return new InstancePage(items, total, page, size);
    }

    /** What the person typed is text, not a pattern. */
    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
