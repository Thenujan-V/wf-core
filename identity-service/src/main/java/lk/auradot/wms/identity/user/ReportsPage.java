package lk.auradot.wms.identity.user;

import java.util.List;

/** The people who report to someone. {@code total} is all of them; {@code items} is cut at a few hundred. */
public record ReportsPage(List<UserSummary> items, long total) {
}
