package lk.auradot.wms.document.requests;

import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.folder.FolderStore;
import lk.auradot.wms.document.folder.Folders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/** Spec §6: /Processes/<processKey>/<instanceId>, made on first use. These folders belong to the system. */
@Component
public class InstanceFolders {

    private final FolderStore folders;
    private final AclSnapshots snapshots;
    private final Clock clock;

    public InstanceFolders(FolderStore folders, AclSnapshots snapshots, Clock clock) {
        this.folders = folders;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    public String ensure(String processKey, String instanceId) {
        return child(child(Folders.PROCESSES_ID, processKey), instanceId);
    }

    public boolean isSystem(String folderId) {
        return snapshots.current().subtree(Folders.PROCESSES_ID).contains(folderId);
    }

    /** Spec §5 file fields: a staged upload's own folder under /Processes/Staged uploads. */
    public String staging(String uploadId) {
        return child(Folders.STAGING_ID, uploadId);
    }

    /**
     * Whether this is a staged upload's folder (directly under /Processes/Staged uploads). Asked of the database, not
     * the cached folder tree: another instance may have created the folder moments ago, and a wrong "no" here would
     * refuse or silently skip the person's file.
     */
    public boolean isStaging(String folderId) {
        return folders.parentOf(folderId).map(Folders.STAGING_ID::equals).orElse(false);
    }

    private String child(String parentId, String name) {
        var existing = folders.findChild(parentId, name);
        if (existing.isPresent()) {
            return existing.get();
        }
        String id = UUID.randomUUID().toString();
        try {
            folders.insertSystem(id, parentId, name, clock.instant());
        } catch (DuplicateKeyException e) {
            return folders.findChild(parentId, name).orElseThrow();
        }
        snapshots.invalidate();
        return id;
    }
}
