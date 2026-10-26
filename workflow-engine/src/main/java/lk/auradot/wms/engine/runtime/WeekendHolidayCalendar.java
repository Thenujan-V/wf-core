package lk.auradot.wms.engine.runtime;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** Working days in one time zone: everything except the weekend days and listed holidays. */
public final class WeekendHolidayCalendar implements WorkCalendar {

    private final ZoneId zone;
    private final Set<DayOfWeek> weekend;
    private final Set<LocalDate> holidays;

    public WeekendHolidayCalendar(ZoneId zone, Set<DayOfWeek> weekend, Set<LocalDate> holidays) {
        if (weekend.containsAll(Set.of(DayOfWeek.values()))) {
            // Otherwise addWorkingDays would search forever for the next working day.
            throw new IllegalArgumentException("A calendar needs at least one working day per week");
        }
        this.zone = zone;
        this.weekend = Set.copyOf(weekend);
        this.holidays = Set.copyOf(holidays);
    }

    @Override
    public Instant addWorkingDays(Instant start, int days) {
        ZonedDateTime current = start.atZone(zone);
        int added = 0;
        while (added < days) {
            current = current.plusDays(1);
            if (!weekend.contains(current.getDayOfWeek()) && !holidays.contains(current.toLocalDate())) {
                added++;
            }
        }
        return current.toInstant();
    }
}
