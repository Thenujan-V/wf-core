package lk.auradot.wms.document.staging;

import lk.auradot.wms.document.document.DocumentRow;
import lk.auradot.wms.document.document.DocumentStore;
import lk.auradot.wms.document.document.VersionRow;
import lk.auradot.wms.document.folder.FolderStore;
import lk.auradot.wms.document.messaging.MessagingConfig;
import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.document.requests.InstanceFolders;
import lk.auradot.wms.document.type.Metadata;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Spec §5 file fields: staged uploads, checked for form-service and attached to the request once it exists. */
@Service
public class StagedFiles {

    private final DocumentStore documents;
    private final FolderStore folders;
    private final InstanceFolders instanceFolders;
    private final Outbox outbox;
    private final Clock clock;

    public StagedFiles(DocumentStore documents, FolderStore folders, InstanceFolders instanceFolders, Outbox outbox,
                       Clock clock) {
        this.documents = documents;
        this.folders = folders;
        this.instanceFolders = instanceFolders;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Files within an hour of the purge are refused: the request would otherwise point at a file about to go. */
    static final Duration LAST_HOUR = Duration.ofHours(1);

    public VerifyResponse verify(String submittedBy, List<String> documentIds) {
        Instant acceptedSince = clock.instant().minus(StagedFilePurge.KEEP.minus(LAST_HOUR));
        List<StagedFile> files = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        for (String id : new LinkedHashSet<>(documentIds)) {
            Optional<DocumentRow> row = documents.find(id)
                    .filter(d -> stagedBy(d, submittedBy) && d.createdAt().isAfter(acceptedSince));
            Optional<VersionRow> version = row.filter(d -> d.currentVersionId() != null)
                    .flatMap(d -> documents.version(id, d.currentVersionId()));
            if (version.isPresent()) {
                files.add(new StagedFile(id, row.get().name(), version.get().size(), version.get().mime()));
            } else {
                unavailable.add(id);
            }
        }
        return new VerifyResponse(files, unavailable);
    }

    /**
     * Inside the caller's transaction: moves the submitter's still-staged files into the request's folder. Files
     * already attached, purged or uploaded by someone else are left alone, so a repeated message changes nothing.
     */
    public void attach(String instanceId, String processKey, String submittedBy, List<String> documentIds, Instant now) {
        for (String id : new LinkedHashSet<>(documentIds)) {
            DocumentRow document = documents.findForUpdate(id).orElse(null);
            if (document == null || !stagedBy(document, submittedBy)) {
                continue;
            }
            String target = instanceFolders.ensure(processKey, instanceId);
            String name = freeName(target, document.name());
            if (!name.equals(document.name())) {
                documents.rename(id, name, Metadata.searchText(name, document.metadata()), now);
            }
            documents.moveToRequest(id, target, instanceId, now);
            folders.delete(document.folderId());
            outbox.add(MessagingConfig.DOCUMENT_LINKED, Map.of("documentId", id, "instanceId", instanceId,
                    "actorId", submittedBy), now);
        }
    }

    private boolean stagedBy(DocumentRow document, String userId) {
        return instanceFolders.isStaging(document.folderId()) && userId.equals(document.createdBy());
    }

    /** "Receipt.pdf", or "Receipt (2).pdf" when the request already has one. */
    private String freeName(String folderId, String name) {
        if (!documents.nameTaken(folderId, name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = base + " (" + n + ")" + extension;
            if (!documents.nameTaken(folderId, candidate)) {
                return candidate;
            }
        }
    }
}
