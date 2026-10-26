package lk.auradot.wms.document.document;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.Names;
import lk.auradot.wms.document.access.Access;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.document.requests.InstanceAccessClient;
import lk.auradot.wms.document.storage.ObjectStore;
import lk.auradot.wms.document.storage.StorageProperties;
import lk.auradot.wms.document.type.Metadata;
import lk.auradot.wms.document.written.DraftStore;
import lk.auradot.wms.document.written.SubmissionStore;
import lk.auradot.wms.document.written.WrittenState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentStore store;
    private final Access access;
    private final Metadata metadata;
    private final ObjectStore objects;
    private final StorageProperties storage;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DocumentAccess documentAccess;
    private final InstanceAccessClient instances;
    private final DraftStore drafts;
    private final SubmissionStore submissions;

    public DocumentService(DocumentStore store, Access access, Metadata metadata, ObjectStore objects,
                           StorageProperties storage, Outbox outbox, TransactionTemplate tx, Clock clock,
                           DocumentAccess documentAccess, InstanceAccessClient instances, DraftStore drafts,
                           SubmissionStore submissions) {
        this.drafts = drafts;
        this.submissions = submissions;
        this.store = store;
        this.access = access;
        this.metadata = metadata;
        this.objects = objects;
        this.storage = storage;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.documentAccess = documentAccess;
        this.instances = instances;
    }

    public List<DocumentSummary> list(String folderId, Caller caller) {
        Permission permission = access.folder(folderId, caller, Permission.READ);
        List<DocumentSummary> all = store.summaries(store.inFolder(folderId));
        // A written document nobody has approved yet is only there for the people who may change it.
        return permission.covers(Permission.WRITE) ? all
                : all.stream().filter(d -> d.kind() != DocumentKind.WRITTEN || d.currentVersion() != null).toList();
    }

    public DocumentView get(String id, Caller caller) {
        DocumentRow document = visible(id, caller);
        Permission permission = documentAccess.permission(document, caller);
        List<VersionView> versions = store.versions(id).stream().map(VersionView::of).toList();
        boolean hasDraft = document.kind() == DocumentKind.WRITTEN && drafts.exists(id);
        return new DocumentView(document.id(), document.folderId(), document.name(), document.typeId(), document.metadata(),
                document.checkedOutBy(), document.checkedOutAt(), document.createdBy(), document.createdAt(),
                document.updatedAt(), permission, document.currentVersionId(), versions,
                document.linkedInstanceId(), documentAccess.inLibrary(document, caller),
                document.kind(), WrittenState.of(document, hasDraft), hasDraft);
    }

    /** Spec §6: a request's documents, for whoever may see the request (and library admins). */
    public List<DocumentSummary> attachments(String instanceId, Caller caller) {
        if (!caller.isAdmin() && instances.access(instanceId, caller).filter(a -> a.canView()).isEmpty()) {
            throw ApiException.notFound("DOC_INSTANCE_NOT_FOUND", "No such request");
        }
        return store.summaries(store.linkedTo(instanceId));
    }

    /** The document if the caller may read it (through its folder or its request); otherwise it does not exist for them. */
    public DocumentRow visible(String id, Caller caller) {
        DocumentRow document = store.find(id).orElseThrow(DocumentService::notFound);
        if (documentAccess.permission(document, caller) == null) {
            throw notFound();
        }
        return document;
    }

    public DocumentView rename(String id, RenameDocumentRequest request, Caller caller) {
        String name = Names.check(request.name());
        mutate(id, caller, Permission.WRITE, document -> {
            try {
                store.rename(id, name, Metadata.searchText(name, document.metadata()), clock.instant());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("DOC_NAME_TAKEN", "Something with this name already exists here");
            }
        });
        return get(id, caller);
    }

    public DocumentView setMetadata(String id, MetadataRequest request, Caller caller) {
        DocumentRow before = visible(id, caller);
        documentAccess.require(before, caller, Permission.WRITE);
        // Validated before the row is locked: form-service is a remote call.
        Map<String, Object> values = metadata.check(request.typeId(), request.metadata(), caller);
        mutate(id, caller, Permission.WRITE, document ->
                store.setMetadata(id, request.typeId(), values, Metadata.searchText(document.name(), values), clock.instant()));
        return get(id, caller);
    }

    public void delete(String id, Caller caller) {
        DocumentAccess.RequestGrant grant = grant(id, caller);
        List<String> keys = tx.execute(status -> {
            DocumentRow document = lockedVisible(id, caller, grant);
            documentAccess.require(document, caller, Permission.DELETE, grant);
            requireNotInApproval(document);
            requireNoOtherHolder(document, caller);
            // The review PDFs of its approval rounds go with it.
            List<String> removed = new java.util.ArrayList<>(submissions.reviewKeysOf(id));
            removed.addAll(store.delete(id));
            outbox.add(MessagingConfig.DOCUMENT_DELETED, Map.of("documentId", id, "folderId", document.folderId(),
                    "actorId", caller.userId()), clock.instant());
            return removed;
        });
        // After the commit: a leftover object is harmless, a missing one for a live version is not.
        for (String key : keys) {
            try {
                objects.delete(key);
            } catch (RuntimeException e) {
                log.warn("Could not delete object {} of deleted document {}: {}", key, id, e.getMessage());
            }
        }
    }

    public DocumentView checkout(String id, Caller caller) {
        DocumentAccess.RequestGrant grant = grant(id, caller);
        tx.executeWithoutResult(status -> {
            DocumentRow document = lockedVisible(id, caller, grant);
            documentAccess.require(document, caller, Permission.WRITE, grant);
            requireNotInApproval(document);
            if (caller.userId().equals(document.checkedOutBy())) {
                return;
            }
            requireNoOtherHolder(document, caller);
            store.setCheckout(id, caller.userId(), clock.instant());
        });
        return get(id, caller);
    }

    /** The holder releases their own lock; a folder manager may force it (spec §6), which is recorded as an event. */
    public DocumentView cancelCheckout(String id, Caller caller) {
        DocumentAccess.RequestGrant grant = grant(id, caller);
        tx.executeWithoutResult(status -> {
            DocumentRow document = lockedVisible(id, caller, grant);
            if (document.checkedOutBy() == null) {
                throw notCheckedOut();
            }
            if (!document.checkedOutBy().equals(caller.userId())) {
                Permission permission = documentAccess.permission(document, caller, grant);
                if (permission == null || !permission.covers(Permission.MANAGE)) {
                    throw checkedOut(document);
                }
                outbox.add(MessagingConfig.CHECKOUT_FORCED, Map.of("documentId", id, "folderId", document.folderId(),
                        "holderId", document.checkedOutBy(), "actorId", caller.userId()), clock.instant());
            }
            store.setCheckout(id, null, null);
        });
        return get(id, caller);
    }

    public ContentLink content(String id, String versionId, boolean inline, Caller caller) {
        visible(id, caller);
        VersionRow version = store.version(id, versionId)
                .orElseThrow(() -> ApiException.notFound("DOC_VERSION_NOT_FOUND", "No such version"));
        boolean showInline = inline && Previews.inline(version.mime());
        Instant expiresAt = clock.instant().plus(storage.downloadExpiry());
        String url = objects.presignDownload(version.s3Key(), version.fileName(), version.mime(), showInline,
                storage.downloadExpiry()).toString();
        return new ContentLink(url, version.fileName(), version.mime(), showInline, expiresAt);
    }

    /**
     * Spec §6: restoring an old version creates a new version with the same file (and its extracted text). Like any
     * new version, only the holder of the check-out creates it, and doing so checks the document back in.
     */
    public DocumentView restore(String id, String versionId, RestoreRequest request, Caller caller) {
        DocumentAccess.RequestGrant grant = grant(id, caller);
        tx.executeWithoutResult(status -> {
            DocumentRow document = lockedVisible(id, caller, grant);
            documentAccess.require(document, caller, Permission.WRITE, grant);
            requireFile(document);
            requireHolder(document, caller);
            VersionRow source = store.version(id, versionId)
                    .orElseThrow(() -> ApiException.notFound("DOC_VERSION_NOT_FOUND", "No such version"));
            int[] number = VersionNumbers.next(store.latest(id), false);
            Instant now = clock.instant();
            String comment = request == null || request.comment() == null || request.comment().isBlank()
                    ? "Restored from version " + source.label() : request.comment().strip();
            VersionRow restored = new VersionRow(UUID.randomUUID().toString(), id, number[0], number[1], source.s3Key(),
                    source.size(), source.mime(), source.sha256(), source.fileName(), caller.userId(), now, comment);
            store.insertVersion(restored);
            store.setCurrent(id, restored.id(), now);
            store.copyText(source.id(), restored.id(), now);
            store.setCheckout(id, null, null);
            DocumentEvents.versionUploaded(outbox, document, restored, now);
        });
        return get(id, caller);
    }

    /** Spec §6 "Send for approval…": the document now also belongs to the request (one request per document). */
    public DocumentView link(String id, String instanceId, Caller caller) {
        // A written document goes to a request only by being sent for approval, which freezes its text and unlocks it
        // when the request ends (written-documents spec §7). Until that exists, attaching would lock it for good.
        requireFile(visible(id, caller));
        DocumentAccess.RequestGrant grant = grant(id, caller);
        documentAccess.requireAttach(instanceId, caller);
        tx.executeWithoutResult(status -> {
            DocumentRow document = lockedVisible(id, caller, grant);
            documentAccess.require(document, caller, Permission.WRITE, grant);
            if (instanceId.equals(document.linkedInstanceId())) {
                return;
            }
            if (document.linkedInstanceId() != null) {
                throw ApiException.conflict("DOC_ALREADY_LINKED", "This document already belongs to another request");
            }
            store.setLinkedInstance(id, instanceId, clock.instant());
            outbox.add(MessagingConfig.DOCUMENT_LINKED, Map.of("documentId", id, "instanceId", instanceId,
                    "actorId", caller.userId()), clock.instant());
        });
        return get(id, caller);
    }

    /** A written document's versions are only ever its approved PDFs (written-documents spec §7). */
    public static void requireFile(DocumentRow document) {
        if (document.kind() == DocumentKind.WRITTEN) {
            throw ApiException.conflict("DOC_WRITTEN", "This document is written in the app; its versions come from approval");
        }
    }

    /** A written document in approval is frozen: its text, its check-out and the document itself (written-documents spec §2). */
    public static void requireNotInApproval(DocumentRow document) {
        if (document.kind() == DocumentKind.WRITTEN && document.linkedInstanceId() != null) {
            throw ApiException.conflict("DOC_IN_APPROVAL", "This document is in approval and cannot be changed until the request finishes");
        }
    }

    /** Someone else's lock blocks every change (spec §6). */
    public static void requireNoOtherHolder(DocumentRow document, Caller caller) {
        if (document.checkedOutBy() != null && !document.checkedOutBy().equals(caller.userId())) {
            throw checkedOut(document);
        }
    }

    /** Only the lock holder checks in a new version (spec §6). */
    public static void requireHolder(DocumentRow document, Caller caller) {
        requireNoOtherHolder(document, caller);
        if (document.checkedOutBy() == null) {
            throw notCheckedOut();
        }
    }

    private void mutate(String id, Caller caller, Permission needed, java.util.function.Consumer<DocumentRow> change) {
        DocumentAccess.RequestGrant grant = grant(id, caller);
        tx.executeWithoutResult(status -> {
            DocumentRow document = lockedVisible(id, caller, grant);
            documentAccess.require(document, caller, needed, grant);
            requireNoOtherHolder(document, caller);
            change.accept(document);
        });
    }

    /** The request's part of the caller's permission, asked before the row is locked: workflow-service is remote. */
    private DocumentAccess.RequestGrant grant(String id, Caller caller) {
        return store.find(id).map(d -> documentAccess.viaRequest(d, caller)).orElse(DocumentAccess.RequestGrant.NONE);
    }

    private DocumentRow lockedVisible(String id, Caller caller, DocumentAccess.RequestGrant grant) {
        DocumentRow document = store.findForUpdate(id).orElseThrow(DocumentService::notFound);
        if (documentAccess.permission(document, caller, grant) == null) {
            throw notFound();
        }
        return document;
    }

    public static ApiException notFound() {
        return ApiException.notFound("DOC_NOT_FOUND", "No such document");
    }

    static ApiException checkedOut(DocumentRow document) {
        return ApiException.conflict("DOC_CHECKED_OUT", "Checked out by someone else; they or a folder manager can release it")
                .with("checkedOutBy", document.checkedOutBy());
    }

    static ApiException notCheckedOut() {
        return ApiException.conflict("DOC_NOT_CHECKED_OUT", "Check the document out first");
    }
}
