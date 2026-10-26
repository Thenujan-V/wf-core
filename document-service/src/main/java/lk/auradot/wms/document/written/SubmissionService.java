package lk.auradot.wms.document.written;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.document.ContentLink;
import lk.auradot.wms.document.document.DocumentAccess;
import lk.auradot.wms.document.document.DocumentKind;
import lk.auradot.wms.document.document.DocumentRow;
import lk.auradot.wms.document.document.DocumentService;
import lk.auradot.wms.document.document.DocumentStore;
import lk.auradot.wms.document.document.DocumentView;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.document.requests.InstanceAccessClient;
import lk.auradot.wms.document.storage.ObjectStore;
import lk.auradot.wms.document.storage.StorageProperties;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Sending a written document for approval, and the PDF its approvers read (written-documents spec §4, §7). */
@Service
public class SubmissionService {

    static final String REVIEW_NOTE = "For review — not approved";
    private static final String PDF = "application/pdf";
    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final DocumentStore documents;
    private final DraftStore drafts;
    private final SubmissionStore submissions;
    private final DocumentService documentService;
    private final DocumentAccess documentAccess;
    private final PdfRenderer renderer;
    private final ObjectStore objects;
    private final StorageProperties storage;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final InstanceAccessClient instances;
    private final ApprovalSettlement settlement;

    public SubmissionService(DocumentStore documents, DraftStore drafts, SubmissionStore submissions,
                             DocumentService documentService, DocumentAccess documentAccess, PdfRenderer renderer,
                             ObjectStore objects, StorageProperties storage, Outbox outbox, TransactionTemplate tx, Clock clock,
                             InstanceAccessClient instances, ApprovalSettlement settlement) {
        this.instances = instances;
        this.settlement = settlement;
        this.documents = documents;
        this.drafts = drafts;
        this.submissions = submissions;
        this.documentService = documentService;
        this.documentAccess = documentAccess;
        this.renderer = renderer;
        this.objects = objects;
        this.storage = storage;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Freezes the text as it stands, makes the PDF approvers will read, and attaches the document to the request.
     * The PDF is made and stored before the document row is locked (both take time); the lock then checks that the
     * text is still the one that was rendered, so what is frozen is exactly what the PDF shows.
     */
    public DocumentView send(String documentId, String instanceId, Caller caller) {
        DocumentRow seen = documentService.visible(documentId, caller);
        if (seen.kind() != DocumentKind.WRITTEN) {
            throw ApiException.conflict("DOC_NOT_WRITTEN", "This document is an uploaded file");
        }
        DocumentAccess.RequestGrant grant = documentAccess.viaRequest(seen, caller);
        documentAccess.require(seen, caller, Permission.WRITE, grant);
        documentAccess.requireAttach(instanceId, caller);
        if (instanceId.equals(seen.linkedInstanceId())) {
            return documentService.get(documentId, caller); // sent again to the same request: already done
        }
        DocumentService.requireNotInApproval(seen);
        DraftRow draft = drafts.find(documentId).orElseThrow(() ->
                ApiException.notFound("DOC_NO_DRAFT", "Nothing is being written here; start a revision first"));
        if (Jsoup.parse(draft.html()).text().isBlank()) {
            throw ApiException.conflict("DOC_EMPTY", "Write something before sending this document for approval");
        }

        byte[] pdf = renderer.render(seen.name(), draft.html(), REVIEW_NOTE);
        String id = UUID.randomUUID().toString();
        String key = "reviews/" + id + ".pdf";
        objects.put(key, pdf, PDF);
        try {
            tx.executeWithoutResult(status -> {
                DocumentRow document = documents.findForUpdate(documentId).orElseThrow(DocumentService::notFound);
                documentAccess.require(document, caller, Permission.WRITE, grant);
                DocumentService.requireNotInApproval(document);
                DocumentService.requireNoOtherHolder(document, caller);
                DraftRow now = drafts.find(documentId).orElseThrow(DocumentService::notFound);
                if (now.revision() != draft.revision()) {
                    throw ApiException.conflict("DOC_DRAFT_CHANGED", "This document was changed while it was being sent; send it again");
                }
                Instant at = clock.instant();
                submissions.insert(new SubmissionRow(id, documentId, instanceId, draft.html(), key, pdf.length, sha256(pdf),
                        SubmissionRow.PENDING, null, caller.userId(), at, null, null));
                documents.setLinkedInstance(documentId, instanceId, at);
                documents.setCheckout(documentId, null, null);
                outbox.add(MessagingConfig.DOCUMENT_LINKED, Map.of("documentId", documentId, "instanceId", instanceId,
                        "actorId", caller.userId()), at);
            });
        } catch (DuplicateKeyException e) {
            discard(key);
            // The unique key on instance_id: another written document is already in approval with this request.
            throw ApiException.conflict("DOC_REQUEST_TAKEN", "This request already has a document in approval");
        } catch (RuntimeException e) {
            discard(key);
            // Two sends at once: the other one won and it was for this request, so this one has nothing left to do.
            DocumentRow after = documents.find(documentId).orElseThrow(DocumentService::notFound);
            if (instanceId.equals(after.linkedInstanceId())) {
                return documentService.get(documentId, caller);
            }
            throw e;
        }
        unlockIfTheRequestIsAlreadyOver(instanceId, caller);
        return documentService.get(documentId, caller);
    }

    /**
     * Making the PDF takes a moment, and the request can end in it (cancelled, or a process that finishes by itself).
     * Its "ended" message then arrived before there was a round to close, and no second one will come. So once the
     * document is locked, the request is asked once more; if it is over, the round is left to the settlement. How it ended
     * is then asked of workflow-service, so an approval in that moment still gets its PDF.
     * If workflow-service cannot answer, the document stays in approval (a folder manager can release it).
     */
    private void unlockIfTheRequestIsAlreadyOver(String instanceId, Caller caller) {
        boolean over;
        try {
            over = instances.fresh(instanceId, caller)
                    .map(request -> !"ACTIVE".equals(request.status()) && !"SUSPENDED".equals(request.status())).orElse(false);
        } catch (RuntimeException e) {
            log.warn("Could not check whether request {} is still running after a document was sent to it: {}", instanceId, e.getMessage());
            return;
        }
        if (over) {
            // How it ended is not known here, so the round is left to the settlement, which asks workflow-service.
            String roundId = tx.execute(status -> submissions.openForInstance(instanceId)
                    .filter(round -> submissions.markEnded(round.id()) || SubmissionRow.ENDED.equals(round.status()))
                    .map(SubmissionRow::id).orElse(null));
            if (roundId != null) {
                settlement.trySettle(roundId);
            }
        }
    }

    /**
     * Takes a document out of approval without waiting for its request: for when the request's "ended" message was
     * lost, which would otherwise leave the document locked for good. Like forcing a check-out, this is for whoever
     * manages the folder, and it is recorded. The round counts as not approved, whatever the request later does.
     */
    public DocumentView release(String documentId, Caller caller) {
        DocumentRow seen = documentService.visible(documentId, caller);
        DocumentAccess.RequestGrant grant = documentAccess.viaRequest(seen, caller);
        documentAccess.require(seen, caller, Permission.MANAGE, grant);
        tx.executeWithoutResult(status -> {
            DocumentRow document = documents.findForUpdate(documentId).orElseThrow(DocumentService::notFound);
            documentAccess.require(document, caller, Permission.MANAGE, grant);
            String instanceId = document.linkedInstanceId();
            if (document.kind() != DocumentKind.WRITTEN || instanceId == null) {
                throw ApiException.conflict("DOC_NOT_IN_APPROVAL", "This document is not in approval");
            }
            Instant now = clock.instant();
            submissions.openForInstance(instanceId)
                    .ifPresent(round -> submissions.decide(round.id(), SubmissionRow.NOT_APPROVED, null, now));
            documents.setLinkedInstance(documentId, null, now);
            outbox.add(MessagingConfig.APPROVAL_RELEASED, Map.of("documentId", documentId, "folderId", document.folderId(),
                    "instanceId", instanceId, "actorId", caller.userId()), now);
        });
        return documentService.get(documentId, caller);
    }

    /** A short-lived link to the PDF of the round that is running, for whoever may see the document. */
    public ContentLink review(String documentId, Caller caller) {
        DocumentRow document = documentService.visible(documentId, caller);
        SubmissionRow round = submissions.openOf(documentId)
                .orElseThrow(() -> ApiException.notFound("DOC_NO_REVIEW", "This document is not in approval"));
        String fileName = document.name() + " (for review).pdf";
        Instant expiresAt = clock.instant().plus(storage.downloadExpiry());
        String url = objects.presignDownload(round.reviewKey(), fileName, PDF, true, storage.downloadExpiry()).toString();
        return new ContentLink(url, fileName, PDF, true, expiresAt);
    }

    /**
     * The request a document was sent to is over. An ending that was not an approval gives the document back at once.
     * An approval is only noted here (the round is ENDED, the document stays in approval): making the approved PDF needs
     * other services, and the caller does that outside this transaction through {@link ApprovalSettlement}. Returns the
     * round to settle, when there is one. Requests this service never sent a document to are none of its business.
     */
    public Optional<String> ended(String instanceId, String endStatus, Instant now) {
        SubmissionRow round = submissions.openForInstance(instanceId).orElse(null);
        if (round == null) {
            return Optional.empty();
        }
        if ("APPROVED".equals(endStatus)) {
            submissions.markEnded(round.id());
            return Optional.of(round.id());
        }
        if (SubmissionRow.PENDING.equals(round.status())) {
            settlement.returnToDraft(round, endStatus, now);
        }
        return Optional.empty();
    }

    private void discard(String key) {
        try {
            objects.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the unused review PDF {}: {}", key, e.getMessage());
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
