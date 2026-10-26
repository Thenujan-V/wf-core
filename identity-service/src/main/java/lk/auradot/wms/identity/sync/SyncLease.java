package lk.auradot.wms.identity.sync;

import lk.auradot.wms.identity.user.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;

/** A time-bounded, non-re-entrant row lease: while held, every other acquire fails until release or expiry. */
@Repository
public class SyncLease {

    private final JdbcClient jdbc;

    public SyncLease(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquire(String name, String owner, Instant now, Duration ttl) {
        return jdbc.sql("""
                        UPDATE sync_lease SET owner = :owner, lease_until = :until
                        WHERE name = :name AND lease_until < :now""")
                .param("owner", owner)
                .param("until", Times.toDb(now.plus(ttl)))
                .param("name", name)
                .param("now", Times.toDb(now))
                .update() == 1;
    }

    /** Expires the lease immediately (back to the epoch) so the next caller never races on equal timestamps. */
    public void release(String name, String owner) {
        jdbc.sql("UPDATE sync_lease SET owner = NULL, lease_until = :expired WHERE name = :name AND owner = :owner")
                .param("expired", Times.toDb(Instant.EPOCH))
                .param("name", name)
                .param("owner", owner)
                .update();
    }
}
