package lk.auradot.wms.document.staging;

import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.document.DocumentStore;
import lk.auradot.wms.document.folder.FolderStore;
import lk.auradot.wms.document.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Staged files nobody submitted within a day are removed, with their stored objects. */
@Component
public class StagedFilePurge {

    static final Duration KEEP = Duration.ofDays(1);

    private static final Logger log = LoggerFactory.getLogger(StagedFilePurge.class);

    private final FolderStore folders;
    private final DocumentStore documents;
    private final ObjectStore objects;
    private final AclSnapshots snapshots;
    private final TransactionTemplate tx;
    private final Clock clock;

    public StagedFilePurge(FolderStore folders, DocumentStore documents, ObjectStore objects, AclSnapshots snapshots,
                           TransactionTemplate tx, Clock clock) {
        this.folders = folders;
        this.documents = documents;
        this.objects = objects;
        this.snapshots = snapshots;
        this.tx = tx;
        this.clock = clock;
    }

    /** Returns how many staged uploads were removed. */
    public int purge() {
        List<String> stale = folders.stagedBefore(clock.instant().minus(KEEP));
        for (String folderId : stale) {
            List<String> keys = tx.execute(status -> {
                List<String> removed = new ArrayList<>();
                // Locked as read: a file attached meanwhile has left the folder and is not deleted.
                for (String documentId : documents.lockInFolder(folderId)) {
                    removed.addAll(documents.delete(documentId));
                }
                folders.delete(folderId);
                return removed;
            });
            for (String key : keys) {
                try {
                    objects.delete(key);
                } catch (RuntimeException e) {
                    log.warn("Could not delete object {} of a purged staged upload: {}", key, e.getMessage());
                }
            }
        }
        if (!stale.isEmpty()) {
            snapshots.invalidate();
        }
        return stale.size();
    }
}
