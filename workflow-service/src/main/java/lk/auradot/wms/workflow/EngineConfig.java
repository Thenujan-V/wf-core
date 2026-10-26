package lk.auradot.wms.workflow;

import lk.auradot.wms.engine.runtime.IdGenerator;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.engine.runtime.RetryPolicy;
import lk.auradot.wms.engine.runtime.WeekendHolidayCalendar;
import lk.auradot.wms.workflow.directory.Directory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDate;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
public class EngineConfig {

    @Bean
    ProcessEngine processEngine(Directory directory, CalendarProperties calendar, Clock clock) {
        var holidays = calendar.holidays().stream().map(LocalDate::parse).collect(Collectors.toSet());
        return new ProcessEngine(directory, new WeekendHolidayCalendar(calendar.zone(), calendar.weekend(), holidays),
                clock, IdGenerator.uuids(), RetryPolicy.defaults());
    }
}
