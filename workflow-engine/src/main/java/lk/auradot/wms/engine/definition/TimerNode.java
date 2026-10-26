package lk.auradot.wms.engine.definition;

import java.time.Duration;

/** Waits for {@code duration}, or until the ISO date held in {@code dueDateVariable}; exactly one is set. */
public record TimerNode(String id, String name, Duration duration, String dueDateVariable) implements Node {
}
