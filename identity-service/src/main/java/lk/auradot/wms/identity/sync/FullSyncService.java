package lk.auradot.wms.identity.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class FullSyncService {

    static final String LEASE = "identity-full-sync";
    static final Duration LEASE_TTL = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(FullSyncService.class);

    private final DirectorySnapshotLoader loader;
    private final DirectorySnapshotApplier applier;
    private final SyncLease lease;
    private final Clock clock;

    public FullSyncService(DirectorySnapshotLoader loader, DirectorySnapshotApplier applier, SyncLease lease, Clock clock) {
        this.loader = loader;
        this.applier = applier;
        this.lease = lease;
        this.clock = clock;
    }

    /**
     * Runs one full sync, or returns empty when another sync holds the lease. Each run takes the lease under its
     * own owner id, so a manual sync is refused while the scheduled one runs in this same process too.
     */
    public Optional<SyncResult> runOnce() {
        String owner = UUID.randomUUID().toString();
        if (!lease.tryAcquire(LEASE, owner, clock.instant(), LEASE_TTL)) {
            log.debug("Directory sync skipped; another sync holds the lease");
            return Optional.empty();
        }
        try {
            SyncResult result = applier.apply(loader.load(), clock.instant());
            log.info("Directory sync applied: {}", result);
            return Optional.of(result);
        } finally {
            lease.release(LEASE, owner);
        }
    }
}
