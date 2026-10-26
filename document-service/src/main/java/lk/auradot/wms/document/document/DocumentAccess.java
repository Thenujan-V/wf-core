package lk.auradot.wms.document.document;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.requests.InstanceAccess;
import lk.auradot.wms.document.requests.InstanceAccessClient;
import lk.auradot.wms.document.requests.InstanceFolders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * What the caller may do with one document: its folder's permission, or (spec §6) for a document that belongs to a
 * request, what the request allows: seeing the request gives READ, being able to attach gives WRITE.
 * <p>
 * Asking workflow-service is a remote call, so it is done before a document row is locked: {@link #viaRequest} works
 * out the request's part, and the locked checks take it as given.
 */
@Component
public class DocumentAccess {

    /** The request's part of a caller's permission on a document, worked out before any row is locked. */
    public record RequestGrant(String instanceId, Permission permission) {

        public static final RequestGrant NONE = new RequestGrant(null, null);
    }

    private final AclSnapshots snapshots;
    private final InstanceAccessClient instances;
    private final InstanceFolders folders;

    public DocumentAccess(AclSnapshots snapshots, InstanceAccessClient instances, InstanceFolders folders) {
        this.snapshots = snapshots;
        this.instances = instances;
        this.folders = folders;
    }

    /**
     * What the document's request gives the caller. Skipped when the folder already gives WRITE or more; when
     * workflow-service cannot answer, someone the folder lets in keeps the folder's permission (only people who reach
     * the document through the request alone get the 503).
     */
    public RequestGrant viaRequest(DocumentRow document, Caller caller) {
        if (document.linkedInstanceId() == null) {
            return RequestGrant.NONE;
        }
        Permission folder = folder(document, caller);
        if (folder != null && folder.covers(Permission.WRITE)) {
            return new RequestGrant(document.linkedInstanceId(), null);
        }
        Permission viaRequest;
        try {
            viaRequest = instances.access(document.linkedInstanceId(), caller)
                    .map(a -> a.canAttach() ? Permission.WRITE : a.canView() ? Permission.READ : null)
                    .orElse(null);
        } catch (ApiException e) {
            if (folder == null || !"DOC_WORKFLOW_UNAVAILABLE".equals(e.code())) {
                throw e;
            }
            viaRequest = null;
        }
        return new RequestGrant(document.linkedInstanceId(), viaRequest);
    }

    public Permission permission(DocumentRow document, Caller caller) {
        return permission(document, caller, viaRequest(document, caller));
    }

    /** Local only: the folder's permission, raised by what the request gives (if it is still the same request). */
    public Permission permission(DocumentRow document, Caller caller, RequestGrant grant) {
        Permission folder = folder(document, caller);
        Permission viaRequest = grant != null && document.linkedInstanceId() != null
                && Objects.equals(grant.instanceId(), document.linkedInstanceId()) ? grant.permission() : null;
        if (folder == null && viaRequest == null) {
            // Spec §5 file fields: a staged upload is readable by the person who uploaded it, and nobody else.
            return folders.isStaging(document.folderId()) && caller.userId().equals(document.createdBy())
                    ? Permission.READ : null;
        }
        if (folder == null || viaRequest == null) {
            return folder == null ? viaRequest : folder;
        }
        return viaRequest.covers(folder) ? viaRequest : folder;
    }

    /** Documents the caller may not read do not exist for them (404); too little permission is a 403. */
    public Permission require(DocumentRow document, Caller caller, Permission needed) {
        return require(document, caller, needed, viaRequest(document, caller));
    }

    public Permission require(DocumentRow document, Caller caller, Permission needed, RequestGrant grant) {
        Permission permission = permission(document, caller, grant);
        if (permission == null) {
            throw DocumentService.notFound();
        }
        if (!permission.covers(needed)) {
            throw new AccessDeniedException("Needs " + needed);
        }
        return permission;
    }

    /** Whether the caller reaches the document through its library folder (not only through a request). */
    public boolean inLibrary(DocumentRow document, Caller caller) {
        return folder(document, caller) != null;
    }

    /** Asks afresh: claiming or finishing a task changes this at once. Never call it while a row is locked. */
    public InstanceAccess requireAttach(String instanceId, Caller caller) {
        InstanceAccess access = instances.fresh(instanceId, caller).filter(InstanceAccess::canView)
                .orElseThrow(() -> ApiException.notFound("DOC_INSTANCE_NOT_FOUND", "No such request"));
        if (!access.canAttach()) {
            throw new AccessDeniedException("This request takes no more documents from you");
        }
        return access;
    }

    private Permission folder(DocumentRow document, Caller caller) {
        Permission folder = snapshots.current().effective(document.folderId(), caller);
        // Written documents (written-documents spec §3): until one is approved it does not exist for people who can
        // only read its folder.
        if (folder != null && document.kind() == DocumentKind.WRITTEN && document.currentVersionId() == null
                && !folder.covers(Permission.WRITE)) {
            return null;
        }
        return folder;
    }
}
