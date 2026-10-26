package lk.auradot.wms.engine.definition;

import java.time.Duration;

/**
 * Task deadline. With {@code businessDays}, {@code dueIn} is a whole number of days counted on the working
 * calendar. {@code reminderPercent} (1-99, optional) triggers a reminder; {@code autoOutcome} is required for
 * AUTO_COMPLETE only.
 */
public record Sla(Duration dueIn, boolean businessDays, Integer reminderPercent, BreachAction onBreach,
                  String autoOutcome) {
}
