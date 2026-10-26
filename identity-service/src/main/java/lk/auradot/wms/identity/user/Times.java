package lk.auradot.wms.identity.user;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** All DATETIME columns hold UTC. */
public final class Times {

    private Times() {
    }

    public static LocalDateTime toDb(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public static Instant fromDb(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
