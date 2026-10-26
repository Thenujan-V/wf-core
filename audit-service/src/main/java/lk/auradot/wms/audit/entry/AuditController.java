package lk.auradot.wms.audit.entry;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;

/** Spec §8: "Query API filters by actor, entity and date range". Reading only: entries are never changed. */
@RestController
public class AuditController {

    static final int MAX_SIZE = 100;

    private final AuditStore store;

    public AuditController(AuditStore store) {
        this.store = store;
    }

    @GetMapping("/entries")
    @PreAuthorize("hasRole('wms-admin')")
    public AuditPage entries(@RequestParam(required = false) String actor,
                             @RequestParam(required = false) String entityType,
                             @RequestParam(required = false) String entityId,
                             @RequestParam(required = false) String action,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("AUDIT_RANGE_INVALID", "The start of the period must be before its end");
        }
        String type = text(entityType);
        return store.search(new AuditFilter(text(actor), type == null ? null : type.toUpperCase(Locale.ROOT), text(entityId),
                text(action), from, to, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE)));
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
