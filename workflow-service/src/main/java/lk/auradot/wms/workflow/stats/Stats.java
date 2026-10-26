package lk.auradot.wms.workflow.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Spec §9 dashboard: throughput, average cycle time and SLA breaches for one period, in total, per process and per
 * day. No names: every signed-in person may see it.
 */
public record Stats(Instant from, Instant to, int days, int offsetMinutes, Totals totals, List<ProcessStats> processes,
                    List<DayStats> daily) {

    /**
     * {@code started}: requests started in the period. {@code completed}/{@code cancelled}: requests that ended in
     * it. {@code active}: in progress or on hold right now. {@code averageCycleSeconds}: start to end of the requests
     * completed in the period, or null when there are none.
     */
    public record Totals(long started, long completed, long cancelled, long active, Double averageCycleSeconds,
                         long slaBreaches) {
    }

    public record ProcessStats(String processKey, String name, long started, long completed, long cancelled,
                               long active, Double averageCycleSeconds, long slaBreaches) {
    }

    /** One of the viewer's days. */
    public record DayStats(LocalDate date, long started, long completed) {
    }
}
