package lk.auradot.wms.engine.runtime;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkCalendarTest {

    private final WorkCalendar colombo = new WeekendHolidayCalendar(ZoneId.of("Asia/Colombo"),
            Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of(LocalDate.parse("2026-10-05")));

    @Test
    void skipsWeekendsAndHolidaysKeepingTheLocalTimeOfDay() {
        // Friday 2026-10-02 15:00 in Colombo (+05:30); Monday 5 Oct is a holiday.
        Instant friday = Instant.parse("2026-10-02T09:30:00Z");

        assertThat(colombo.addWorkingDays(friday, 1)).isEqualTo(Instant.parse("2026-10-06T09:30:00Z"));
        assertThat(colombo.addWorkingDays(friday, 2)).isEqualTo(Instant.parse("2026-10-07T09:30:00Z"));
    }

    @Test
    void aCalendarWithoutWorkingDaysIsRejectedInsteadOfLoopingForever() {
        assertThatThrownBy(() -> new WeekendHolidayCalendar(ZoneId.of("UTC"), Set.of(DayOfWeek.values()), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("working day");
    }

    @Test
    void startingOnAWeekendCountsFromTheNextWorkingDay() {
        Instant saturday = Instant.parse("2026-10-03T04:30:00Z");

        assertThat(colombo.addWorkingDays(saturday, 1)).isEqualTo(Instant.parse("2026-10-06T04:30:00Z"));
    }
}
