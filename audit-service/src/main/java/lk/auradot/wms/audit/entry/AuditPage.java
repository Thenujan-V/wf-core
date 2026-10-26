package lk.auradot.wms.audit.entry;

import java.util.List;

public record AuditPage(List<AuditEntry> items, long total, int page, int size) {
}
