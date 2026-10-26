package lk.auradot.wms.notification.plan;

import java.util.Map;

/** One person to tell about one event: the kind, the values for its texts, and where in the app it leads. */
public record Planned(String userId, Kind kind, Map<String, String> values, String link) {
}
