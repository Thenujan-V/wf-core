package lk.auradot.wms.workflow.stats;

import lk.auradot.wms.workflow.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** A handful of grouped queries; the indexes of V2 keep them to the period asked for. */
@Repository
public class StatsQueries {

    private static final BigDecimal MICROS_PER_SECOND = BigDecimal.valueOf(1_000_000);

    /** What is counted for one process while the queries run. */
    private static final class Counts {
        long started;
        long completed;
        long cancelled;
        long active;
        long breaches;
        BigDecimal cycleMicros = BigDecimal.ZERO;
    }

    private final JdbcClient jdbc;

    public StatsQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The last {@code days} whole days in the viewer's time zone ({@code offsetMinutes} east of UTC), ending now. */
    @Transactional(readOnly = true) // one snapshot, so the totals and the per-day counts agree
    public Stats stats(Instant now, int days, int offsetMinutes) {
        ZoneOffset zone = ZoneOffset.ofTotalSeconds(offsetMinutes * 60);
        LocalDate today = now.atOffset(zone).toLocalDate();
        LocalDate firstDay = today.minusDays(days - 1L);
        Instant from = firstDay.atStartOfDay().toInstant(zone);
        Map<String, Object> period = Map.of("from", Times.toDb(from), "to", Times.toDb(now), "offset", offsetMinutes);

        Map<String, Counts> byProcess = new TreeMap<>();
        jdbc.sql("""
                        SELECT process_key, COUNT(*) FROM process_instance
                        WHERE started_at >= :from AND started_at <= :to GROUP BY process_key""")
                .params(period).query(rs -> {
                    counts(byProcess, rs.getString(1)).started = rs.getLong(2);
                });
        jdbc.sql("""
                        SELECT process_key, status, COUNT(*), SUM(TIMESTAMPDIFF(MICROSECOND, started_at, ended_at))
                        FROM process_instance
                        WHERE ended_at >= :from AND ended_at <= :to AND status IN ('COMPLETED', 'CANCELLED')
                        GROUP BY process_key, status""")
                .params(period).query(rs -> {
                    Counts counts = counts(byProcess, rs.getString(1));
                    if ("COMPLETED".equals(rs.getString(2))) {
                        counts.completed = rs.getLong(3);
                        counts.cycleMicros = rs.getBigDecimal(4) == null ? BigDecimal.ZERO : rs.getBigDecimal(4);
                    } else {
                        counts.cancelled = rs.getLong(3);
                    }
                });
        jdbc.sql("SELECT process_key, COUNT(*) FROM process_instance WHERE status IN ('ACTIVE', 'SUSPENDED') GROUP BY process_key")
                .query(rs -> {
                    counts(byProcess, rs.getString(1)).active = rs.getLong(2);
                });
        jdbc.sql("""
                        SELECT i.process_key, COUNT(*) FROM history_event h JOIN process_instance i ON i.id = h.instance_id
                        WHERE h.event_type = 'TaskOverdue' AND h.occurred_at >= :from AND h.occurred_at <= :to
                        GROUP BY i.process_key""")
                .params(period).query(rs -> {
                    counts(byProcess, rs.getString(1)).breaches = rs.getLong(2);
                });

        Map<String, String> names = new HashMap<>();
        jdbc.sql("""
                        SELECT d.process_key, d.name FROM process_definition d
                        WHERE d.version = (SELECT MAX(x.version) FROM process_definition x WHERE x.process_key = d.process_key)""")
                .query(rs -> {
                    names.put(rs.getString(1), rs.getString(2));
                });

        List<Stats.ProcessStats> processes = new ArrayList<>();
        Counts all = new Counts();
        byProcess.forEach((key, c) -> {
            processes.add(new Stats.ProcessStats(key, names.getOrDefault(key, key), c.started, c.completed, c.cancelled,
                    c.active, average(c), c.breaches));
            all.started += c.started;
            all.completed += c.completed;
            all.cancelled += c.cancelled;
            all.active += c.active;
            all.breaches += c.breaches;
            all.cycleMicros = all.cycleMicros.add(c.cycleMicros);
        });
        processes.sort(Comparator.comparingLong(Stats.ProcessStats::started).reversed()
                .thenComparing(Stats.ProcessStats::processKey));

        Map<LocalDate, long[]> perDay = new HashMap<>();
        jdbc.sql("""
                        SELECT DATE(DATE_ADD(started_at, INTERVAL :offset MINUTE)) AS day, COUNT(*) FROM process_instance
                        WHERE started_at >= :from AND started_at <= :to GROUP BY day""")
                .params(period).query(rs -> {
                    perDay.computeIfAbsent(rs.getObject(1, LocalDate.class), d -> new long[2])[0] = rs.getLong(2);
                });
        jdbc.sql("""
                        SELECT DATE(DATE_ADD(ended_at, INTERVAL :offset MINUTE)) AS day, COUNT(*) FROM process_instance
                        WHERE ended_at >= :from AND ended_at <= :to AND status = 'COMPLETED' GROUP BY day""")
                .params(period).query(rs -> {
                    perDay.computeIfAbsent(rs.getObject(1, LocalDate.class), d -> new long[2])[1] = rs.getLong(2);
                });
        List<Stats.DayStats> daily = new ArrayList<>();
        for (LocalDate day = firstDay; !day.isAfter(today); day = day.plusDays(1)) {
            long[] counted = perDay.getOrDefault(day, new long[2]);
            daily.add(new Stats.DayStats(day, counted[0], counted[1]));
        }

        Stats.Totals totals = new Stats.Totals(all.started, all.completed, all.cancelled, all.active, average(all), all.breaches);
        return new Stats(from, now, days, offsetMinutes, totals, processes, daily);
    }

    private static Counts counts(Map<String, Counts> byProcess, String processKey) {
        return byProcess.computeIfAbsent(processKey, key -> new Counts());
    }

    /** Null, not zero, when nothing was completed: "no time" is not "no data". */
    private static Double average(Counts counts) {
        if (counts.completed == 0) {
            return null;
        }
        return counts.cycleMicros.divide(BigDecimal.valueOf(counts.completed).multiply(MICROS_PER_SECOND), 3, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
