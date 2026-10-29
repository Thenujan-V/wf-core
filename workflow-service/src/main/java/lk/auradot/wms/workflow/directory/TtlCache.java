package lk.auradot.wms.workflow.directory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** A small time-bounded memo for directory answers; a null answer (404) is cached too, so misses stay cheap. */
final class TtlCache {

    private record Entry(Object value, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    TtlCache(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @SuppressWarnings("unchecked")
    <T> T get(String key, Supplier<T> loader) {
        Instant now = clock.instant();
        Entry entry = entries.get(key);
        if (entry != null && entry.expiresAt().isAfter(now)) {
            return (T) entry.value();
        }
        T value = loader.get(); // a failure propagates and nothing is cached
        if (!ttl.isZero()) {
            entries.put(key, new Entry(value, now.plus(ttl)));
        }
        return value;
    }
}
