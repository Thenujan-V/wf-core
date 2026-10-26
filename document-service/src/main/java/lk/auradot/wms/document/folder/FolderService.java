package lk.auradot.wms.document.folder;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.document.Names;
import lk.auradot.wms.document.access.Access;
import lk.auradot.wms.document.access.AclEntry;
import lk.auradot.wms.document.access.AclSnapshot;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.FolderNode;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.access.PrincipalType;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.document.requests.InstanceFolders;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FolderService {

    private final FolderStore store;
    private final AclSnapshots snapshots;
    private final Access access;
    private final Clock clock;
    private final InstanceFolders system;
    private final Outbox outbox;

    public FolderService(FolderStore store, AclSnapshots snapshots, Access access, Clock clock,
                         InstanceFolders system, Outbox outbox) {
        this.outbox = outbox;
        this.store = store;
        this.snapshots = snapshots;
        this.access = access;
        this.clock = clock;
        this.system = system;
    }

    public FolderView view(String id, Caller caller) {
        Permission permission = access.folder(id, caller, Permission.READ);
        AclSnapshot acl = snapshots.current();
        FolderNode node = acl.folder(id).orElseThrow();
        List<FolderView.PathItem> path = acl.ancestors(id).stream()
                .map(f -> new FolderView.PathItem(f.id(), f.name())).toList();
        List<FolderView.PathItem> children = acl.children(id).stream()
                .filter(child -> acl.effective(child.id(), caller) != null)
                .map(f -> new FolderView.PathItem(f.id(), f.name())).toList();
        return new FolderView(node.id(), node.name(), node.parentId(), node.inheritAcl(), permission, path, children);
    }

    @Transactional
    public FolderView create(CreateFolderRequest request, Caller caller) {
        access.folder(request.parentId(), caller, Permission.WRITE);
        requireOrdinary(request.parentId());
        String id = UUID.randomUUID().toString();
        try {
            store.insert(id, request.parentId(), Names.check(request.name()), caller.userId(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        snapshots.invalidate();
        return view(id, caller);
    }

    @Transactional
    public FolderView rename(String id, RenameRequest request, Caller caller) {
        access.folder(id, caller, Permission.WRITE);
        requireOrdinary(id);
        try {
            store.rename(id, Names.check(request.name()));
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        snapshots.invalidate();
        return view(id, caller);
    }

    @Transactional
    public void delete(String id, Caller caller) {
        access.folder(id, caller, Permission.DELETE);
        requireOrdinary(id);
        if (Folders.ROOT_ID.equals(id)) {
            throw ApiException.conflict("DOC_ROOT_FOLDER", "The library root cannot be deleted");
        }
        if (store.hasContent(id)) {
            throw ApiException.conflict("DOC_FOLDER_NOT_EMPTY", "Only an empty folder can be deleted");
        }
        store.delete(id);
        snapshots.invalidate();
    }

    public AclView acl(String id, Caller caller) {
        access.folder(id, caller, Permission.MANAGE);
        AclSnapshot acl = snapshots.current();
        return new AclView(acl.folder(id).orElseThrow().inheritAcl(), acl.entriesOf(id), acl.inherited(id));
    }

    @Transactional
    public AclView setAcl(String id, AclRequest request, Caller caller) {
        access.folder(id, caller, Permission.MANAGE);
        requireOrdinary(id);
        List<AclEntry> entries = request.entries().stream()
                .map(e -> new AclEntry(e.principalType(), e.principalId().strip(), e.permission())).toList();
        Set<String> seen = new HashSet<>();
        for (AclEntry e : entries) {
            if (!seen.add(e.principalType() + ":" + e.principalId())) {
                throw ApiException.badRequest("DOC_ACL_DUPLICATE", "Each person, group or role can appear only once");
            }
        }
        // The root has no parent to inherit from.
        boolean inherit = Folders.ROOT_ID.equals(id) || request.inheritAcl();
        Permission after = snapshots.current().withAcl(id, inherit, entries).effective(id, caller);
        if (after == null || !after.covers(Permission.MANAGE)) {
            throw ApiException.conflict("DOC_ACL_LOCKOUT", "This change would take away your own right to manage the folder");
        }
        Set<String> hadAccess = snapshots.current().entriesOf(id).stream()
                .filter(e -> e.principalType() == PrincipalType.USER).map(AclEntry::principalId).collect(Collectors.toSet());
        String folderName = snapshots.current().folder(id).map(FolderNode::name).orElse("");
        store.replaceAcl(id, inherit, entries);
        // Spec §8 "document-shared": each person who gains access of their own is told (groups and roles are not).
        for (AclEntry entry : entries) {
            if (entry.principalType() == PrincipalType.USER && !hadAccess.contains(entry.principalId())) {
                outbox.add(MessagingConfig.FOLDER_SHARED, Map.of("folderId", id, "folderName", folderName,
                        "userId", entry.principalId(), "permission", entry.permission().name(),
                        "actorId", caller.userId()), clock.instant());
            }
        }
        snapshots.invalidate();
        return acl(id, caller);
    }

    private static ApiException nameTaken() {
        return ApiException.conflict("DOC_NAME_TAKEN", "Something with this name already exists here");
    }

    private void requireOrdinary(String folderId) {
        if (system.isSystem(folderId)) {
            throw ApiException.conflict("DOC_SYSTEM_FOLDER", "Request folders are managed by the system");
        }
    }
}
