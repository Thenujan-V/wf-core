package lk.auradot.wms.document.access;

import lk.auradot.wms.document.folder.FolderStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The folder tree and ACL, reloaded at most every few seconds (spec §6 "cached briefly"). Changes made through this
 * instance invalidate it at once; changes on another replica show up within the cache time.
 */
@Component
public class AclSnapshots {

    private record Loaded(AclSnapshot snapshot, Instant at) {
    }

    private final FolderStore folders;
    private final Clock clock;
    private final Duration ttl;
    private volatile Loaded loaded;

    public AclSnapshots(FolderStore folders, Clock clock, @Value("${wms.document.acl-cache:PT5S}") Duration ttl) {
        this.folders = folders;
        this.clock = clock;
        this.ttl = ttl;
    }

    public AclSnapshot current() {
        Loaded current = loaded;
        Instant now = clock.instant();
        if (current == null || !now.isBefore(current.at().plus(ttl)) || now.isBefore(current.at())) {
            current = new Loaded(new AclSnapshot(folders.loadNodes(), folders.loadEntries()), now);
            loaded = current;
        }
        return current.snapshot();
    }

    /**
     * Drops the cached snapshot now and, inside a transaction, again when it ends: a request that reloads before the
     * commit sees the old rows, and must not keep them for the rest of the cache time.
     */
    public void invalidate() {
        loaded = null;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    loaded = null;
                }
            });
        }
    }
}
