package lk.auradot.wms.document.written;

import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.document.approval.ApprovalUnavailableException;
import lk.auradot.wms.document.approval.People;
import lk.auradot.wms.document.approval.RequestOutcome;
import lk.auradot.wms.document.approval.RequestOutcomes;
import lk.auradot.wms.document.document.DocumentEvents;
import lk.auradot.wms.document.document.DocumentRow;
import lk.auradot.wms.document.document.DocumentStore;
import lk.auradot.wms.document.document.VersionNumbers;
import lk.auradot.wms.document.document.VersionRow;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.document.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Settles a round of approval once its request is over (written-documents spec §8): asks workflow-service how it
 * ended, and either makes the approved PDF the document's next version or gives the document back as a draft.
 * <p>
 * Everything slow or remote (asking the two services, making and storing the PDF) happens before the document row is
 * locked. If any of it fails, nothing has been decided: the round stays as it was and is settled on a later pass. An
 * approved document is never published without its record, and never returned to draft because a service was down.
 */
@Service
public class ApprovalSettlement {

    private static final String PDF = "application/pdf";
    private static final Logger log = LoggerFactory.getLogger(ApprovalSettlement.class);

    private final SubmissionStore submissions;
    private final DocumentStore documents;
    private final DraftStore drafts;
    private final RequestOutcomes outcomes;
    private final People people;
    private final PdfRenderer renderer;
    private final ObjectStore objects;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ApprovalProperties properties;

    public ApprovalSettlement(SubmissionStore submissions, DocumentStore documents, DraftStore drafts, RequestOutcomes outcomes,
                              People people, PdfRenderer renderer, ObjectStore objects, Outbox outbox, TransactionTemplate tx,
                              Clock clock, ApprovalProperties properties) {
        this.properties = properties;
        this.submissions = submissions;
        this.documents = documents;
        this.drafts = drafts;
        this.outcomes = outcomes;
        this.people = people;
        this.renderer = renderer;
        this.objects = objects;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    /** Like {@link #settle}, for callers that must not fail: false means "not now", and a later pass tries again. */
    public boolean trySettle(String roundId) {
        try {
            settle(roundId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Approval round {} could not be settled yet; it is tried again later: {}", roundId, e.getMessage());
            return false;
        }
    }

    /** Settles every round whose request is over; returns how many were settled now. */
    public int settleEnded() {
        int settled = 0;
        for (SubmissionRow round : submissions.ended()) {
            try {
                settle(round.id());
                settled++;
            } catch (ApprovalUnavailableException e) {
                // A service is not answering: asking about the other rounds would only wait for the same silence.
                log.warn("Stopped settling approvals for now; the rest waits for the next pass: {}", e.getMessage());
                break;
            } catch (RuntimeException e) {
                log.warn("Approval round {} could not be settled yet; it is tried again later: {}", round.id(), e.getMessage());
            }
        }
        return settled;
    }

    /**
     * Looks again at rounds that have waited long without hearing that their request ended: the message may have been
     * lost. A request that is over is settled; one that still runs is noted as checked and left for a later pass.
     * Returns how many rounds were settled.
     */
    public int sweepStale() {
        Instant now = clock.instant();
        int settled = 0;
        for (SubmissionRow round : submissions.stalePending(now.minus(properties.staleAfter()))) {
            try {
                RequestOutcome outcome = outcomes.outcome(round.instanceId()).orElse(null);
                if (outcome != null && outcome.running()) {
                    submissions.touch(round.id(), now);
                    continue;
                }
                settle(round.id());
                settled++;
            } catch (ApprovalUnavailableException e) {
                log.warn("Stopped checking on approvals for now; the rest waits for the next pass: {}", e.getMessage());
                break;
            } catch (RuntimeException e) {
                log.warn("Could not check approval round {}; it is checked again later: {}", round.id(), e.getMessage());
            }
        }
        return settled;
    }

    /**
     * Settles one open round. A request workflow-service does not know, or that ended any other way than APPROVED, is
     * not approved. A request that turns out to be still running is left alone. Throws when a service cannot answer or
     * the PDF cannot be made or stored: nothing was decided.
     */
    public void settle(String roundId) {
        SubmissionRow round = submissions.find(roundId).orElse(null);
        if (round == null || !(SubmissionRow.PENDING.equals(round.status()) || SubmissionRow.ENDED.equals(round.status()))) {
            return;
        }
        RequestOutcome outcome = outcomes.outcome(round.instanceId()).orElse(null);
        if (outcome != null && outcome.running()) {
            return;
        }
        if (outcome == null || !"APPROVED".equals(outcome.endStatus())) {
            String endStatus = outcome == null ? null : outcome.endStatus();
            tx.executeWithoutResult(status -> returnToDraft(round, endStatus, clock.instant()));
            return;
        }
        approve(round, outcome);
    }

    /**
     * Closes the round as not approved and unlocks the document, with its draft as it was. Runs inside the caller's
     * transaction. Does nothing when the round was already closed.
     */
    public void returnToDraft(SubmissionRow round, String endStatus, Instant now) {
        DocumentRow document = documents.findForUpdate(round.documentId()).orElse(null);
        if (!submissions.decide(round.id(), SubmissionRow.NOT_APPROVED, endStatus, now) || document == null) {
            return;
        }
        if (round.instanceId().equals(document.linkedInstanceId())) {
            documents.setLinkedInstance(document.id(), null, now);
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("documentId", document.id());
        event.put("folderId", document.folderId());
        event.put("name", document.name());
        event.put("instanceId", round.instanceId());
        event.put("endStatus", endStatus);
        event.put("submittedBy", round.submittedBy());
        outbox.add(MessagingConfig.DOCUMENT_RETURNED, event, now);
    }

    private void approve(SubmissionRow round, RequestOutcome outcome) {
        DocumentRow seen = documents.find(round.documentId()).orElse(null);
        if (seen == null) {
            return; // the rows of a deleted document went with it
        }
        int[] number = VersionNumbers.next(documents.latest(seen.id()), true);
        String label = number[0] + "." + number[1];
        List<ApprovalRecord.Step> steps = outcome.steps().stream()
                .map(s -> new ApprovalRecord.Step(s.name(), s.completedBy() == null ? null : people.displayName(s.completedBy()),
                        s.outcome(), s.completedAt()))
                .toList();
        ApprovalRecord record = new ApprovalRecord(seen.name(), label, outcome.reference(), outcome.processName(),
                people.displayName(round.submittedBy()), round.submittedAt(), steps, outcome.endedAt());
        byte[] pdf = renderer.render(seen.name(), round.html(), seen.name() + " · version " + label, record);
        String versionId = UUID.randomUUID().toString();
        String key = "objects/" + versionId;
        objects.put(key, pdf, PDF);
        boolean published;
        try {
            published = Boolean.TRUE.equals(tx.execute(status -> {
                DocumentRow document = documents.findForUpdate(seen.id()).orElse(null);
                if (document == null) {
                    return false;
                }
                // First: was the round settled by someone else while this PDF was being made? Then there is nothing to do.
                String standing = submissions.find(round.id()).map(SubmissionRow::status).orElse(null);
                if (!SubmissionRow.PENDING.equals(standing) && !SubmissionRow.ENDED.equals(standing)) {
                    return false;
                }
                // Checked under the lock: the name and number printed in the PDF must be the ones it is filed under.
                int[] now = VersionNumbers.next(documents.latest(document.id()), true);
                if (!Arrays.equals(now, number) || !document.name().equals(seen.name())) {
                    throw new IllegalStateException("The document changed while its approved PDF was being made");
                }
                Instant at = clock.instant();
                if (!submissions.approve(round.id(), outcome.endStatus(), versionId, at)) {
                    return false; // settled by someone else in the meantime
                }
                VersionRow version = new VersionRow(versionId, document.id(), number[0], number[1], key, pdf.length, PDF, sha256(pdf),
                        fileName(document.name()), round.submittedBy(), at, "Approved in request " + outcome.reference());
                documents.insertVersion(version);
                documents.setCurrent(document.id(), versionId, at);
                drafts.delete(document.id());
                if (round.instanceId().equals(document.linkedInstanceId())) {
                    documents.setLinkedInstance(document.id(), null, at);
                }
                documents.setCheckout(document.id(), null, null);
                DocumentEvents.versionUploaded(outbox, document, version, at);
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("documentId", document.id());
                event.put("folderId", document.folderId());
                event.put("name", document.name());
                event.put("versionId", versionId);
                event.put("label", label);
                event.put("instanceId", round.instanceId());
                event.put("submittedBy", round.submittedBy());
                outbox.add(MessagingConfig.DOCUMENT_APPROVED, event, at);
                return true;
            }));
        } catch (RuntimeException e) {
            discard(key);
            throw e;
        }
        if (!published) {
            discard(key);
        }
    }

    private void discard(String key) {
        try {
            objects.delete(key);
        } catch (RuntimeException e) {
            log.warn("Could not delete the unused approved PDF {}: {}", key, e.getMessage());
        }
    }

    private static String fileName(String documentName) {
        return documentName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") ? documentName : documentName + ".pdf";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
