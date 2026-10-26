package lk.auradot.wms.document.access;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** Folders the caller cannot read do not exist for them (404); too little permission on a visible one is a 403. */
@Component
public class Access {

    private final AclSnapshots snapshots;

    public Access(AclSnapshots snapshots) {
        this.snapshots = snapshots;
    }

    public Permission folder(String folderId, Caller caller, Permission needed) {
        Permission effective = snapshots.current().effective(folderId, caller);
        if (effective == null) {
            throw ApiException.notFound("DOC_FOLDER_NOT_FOUND", "No such folder");
        }
        if (!effective.covers(needed)) {
            throw new AccessDeniedException("Needs " + needed);
        }
        return effective;
    }
}
