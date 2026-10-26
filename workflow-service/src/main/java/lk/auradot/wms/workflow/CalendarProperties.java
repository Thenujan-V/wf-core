package lk.auradot.wms.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.Set;

/** The customer's working calendar; holidays are ISO dates (yyyy-MM-dd). */
@ConfigurationProperties("wms.workflow.calendar")
public record CalendarProperties(@DefaultValue("UTC") ZoneId zone,
                                 @DefaultValue({"SATURDAY", "SUNDAY"}) Set<DayOfWeek> weekend,
                                 @DefaultValue Set<String> holidays) {
}
