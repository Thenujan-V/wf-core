package lk.auradot.wms.document.written;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.Names;
import lk.auradot.wms.document.access.Access;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.document.DocumentAccess;
import lk.auradot.wms.document.document.DocumentKind;
import lk.auradot.wms.document.document.DocumentRow;
import lk.auradot.wms.document.document.DocumentService;
import lk.auradot.wms.document.document.DocumentStore;
import lk.auradot.wms.document.document.DocumentView;
import lk.auradot.wms.document.requests.InstanceFolders;
import lk.auradot.wms.document.type.Metadata;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Documents written in the app (written-documents spec): creating one, and reading and saving its text. */
@Service
public class WrittenDocumentService {

    private final DocumentStore documents;
    private final DraftStore drafts;
    private final DocumentService documentService;
    private final DocumentAccess documentAccess;
    private final Access access;
    private final InstanceFolders instanceFolders;
    private final HtmlCleaner cleaner;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WrittenDocumentService(DocumentStore documents, DraftStore drafts, DocumentService documentService,
                                  DocumentAccess documentAccess, Access access, InstanceFolders instanceFolders,
                                  HtmlCleaner cleaner, TransactionTemplate tx, Clock clock) {
        this.documents = documents;
        this.drafts = drafts;
        this.documentService = documentService;
        this.documentAccess = documentAccess;
        this.access = access;
        this.instanceFolders = instanceFolders;
        this.cleaner = cleaner;
        this.tx = tx;
        this.clock = clock;
    }

    public DocumentView create(CreateWrittenRequest request, Caller caller) {
        String name = Names.check(request.name());
        String folderId = request.folderId().strip();
        if (instanceFolders.isSystem(folderId)) {
            throw ApiException.conflict("DOC_SYSTEM_FOLDER", "Request folders are managed by the system");
        }
        access.folder(folderId, caller, Permission.WRITE);
        String id = UUID.randomUUID().toString();
        Instant now = clock.instant();
        try {
            tx.executeWithoutResult(status -> {
                documents.insert(new DocumentRow(id, folderId, name, null, Map.of(), null, null, null, null,
                        caller.userId(), now, now, DocumentKind.WRITTEN), Metadata.searchText(name, Map.of()));
                drafts.insert(new DraftRow(id, "", 0, caller.userId(), now));
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DOC_NAME_TAKEN", "Something with this name already exists here");
        }
        return documentService.get(id, caller);
    }

    /** The text being worked on, for people who may change the document. */
    public DraftView draft(String id, Caller caller) {
        DocumentRow document = documentService.visible(id, caller);
        documentAccess.require(document, caller, Permission.WRITE);
        requireWritten(document);
        DraftRow draft = drafts.find(id).orElseThrow(WrittenDocumentService::noDraft);
        return view(draft, document, caller);
    }

    /**
     * Stores the text. Only the holder of the check-out saves, never while the document is in approval, and only over
     * the revision they last saw: a second tab or a late retry gets a conflict instead of overwriting newer text.
     */
    public DraftView save(String id, SaveDraftRequest request, Caller caller) {
        // Cleaned (and measured) before the row is locked.
        String html = cleaner.clean(request.html());
        DocumentAccess.RequestGrant grant = documents.find(id).map(d -> documentAccess.viaRequest(d, caller))
                .orElse(DocumentAccess.RequestGrant.NONE);
        return tx.execute(status -> {
            DocumentRow document = documents.findForUpdate(id).orElseThrow(DocumentService::notFound);
            if (documentAccess.permission(document, caller, grant) == null) {
                throw DocumentService.notFound();
            }
            documentAccess.require(document, caller, Permission.WRITE, grant);
            requireWritten(document);
            DocumentService.requireNotInApproval(document);
            DocumentService.requireHolder(document, caller);
            if (!drafts.exists(id)) {
                throw noDraft();
            }
            if (!drafts.update(id, html, request.revision(), caller.userId(), clock.instant())) {
                DraftRow stored = drafts.find(id).orElseThrow();
                if (!sameSaveAgain(stored, html, request.revision(), caller)) {
                    throw ApiException.conflict("DOC_DRAFT_CHANGED", "This document was changed somewhere else; reload to see the latest text");
                }
                return view(stored, document, caller);
            }
            return view(drafts.find(id).orElseThrow(), document, caller);
        });
    }

    /**
     * A browser that never got the answer to its last save sends it again. That is the same person storing the same
     * text over the revision the stored one was made from, and it has already happened, so it is not a conflict.
     */
    private static boolean sameSaveAgain(DraftRow stored, String html, int revision, Caller caller) {
        return stored.revision() == revision + 1 && stored.updatedBy().equals(caller.userId()) && stored.html().equals(html);
    }

    private static DraftView view(DraftRow draft, DocumentRow document, Caller caller) {
        boolean editable = WrittenState.of(document, true).editable() && caller.userId().equals(document.checkedOutBy());
        return new DraftView(draft.html(), draft.revision(), draft.updatedBy(), draft.updatedAt(), editable);
    }

    private static void requireWritten(DocumentRow document) {
        if (document.kind() != DocumentKind.WRITTEN) {
            throw ApiException.conflict("DOC_NOT_WRITTEN", "This document is an uploaded file; it has no text to edit here");
        }
    }

    private static ApiException noDraft() {
        return ApiException.notFound("DOC_NO_DRAFT", "Nothing is being written here; start a revision first");
    }
}
