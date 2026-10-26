package lk.auradot.wms.engine.runtime;

import java.time.Duration;
import java.time.Instant;

public interface WorkCalendar {

    /** {@code start} plus {@code days} working days, keeping the time of day. */
    Instant addWorkingDays(Instant start, int days);

    /** Every day is a working day. */
    static WorkCalendar calendarDays() {
        return (start, days) -> start.plus(Duration.ofDays(days));
    }
}
