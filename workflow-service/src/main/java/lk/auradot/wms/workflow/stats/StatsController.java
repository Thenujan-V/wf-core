package lk.auradot.wms.workflow.stats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Spec §9 dashboard. Totals only, so every signed-in person may ask. */
@RestController
public class StatsController {

    static final int MAX_DAYS = 366;
    /** UTC−14:00 … UTC+14:00 covers every time zone. */
    static final int MAX_OFFSET_MINUTES = 14 * 60;
    /** More periods than this are not asked for in half a minute; if they are, the answers are simply forgotten. */
    static final int MAX_CACHED = 200;

    /** An answer, and until when it may be given again. */
    private record Cached(Stats stats, Instant until) {
    }

    private final StatsQueries queries;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public StatsController(StatsQueries queries, Clock clock,
                           @Value("${wms.workflow.stats.cache-ttl:PT30S}") Duration cacheTtl) {
        this.queries = queries;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
    }

    /**
     * The figures are the same for everyone and cost several queries, so each period's answer is reused for a short
     * while ({@code wms.workflow.stats.cache-ttl}; zero switches that off).
     */
    @GetMapping("/stats")
    public Stats stats(@RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "0") int offsetMinutes) {
        int period = Math.min(Math.max(days, 1), MAX_DAYS);
        int offset = Math.min(Math.max(offsetMinutes, -MAX_OFFSET_MINUTES), MAX_OFFSET_MINUTES);
        Instant now = clock.instant();
        if (cacheTtl.isZero() || cacheTtl.isNegative()) {
            return queries.stats(now, period, offset);
        }
        String key = period + "/" + offset;
        Cached cached = cache.get(key);
        if (cached != null && cached.until().isAfter(now)) {
            return cached.stats();
        }
        Stats fresh = queries.stats(now, period, offset);
        if (cache.size() >= MAX_CACHED) {
            cache.clear();
        }
        cache.put(key, new Cached(fresh, now.plus(cacheTtl)));
        return fresh;
    }
}
