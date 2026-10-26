package lk.auradot.wms.document.upload;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.Names;
import lk.auradot.wms.document.access.Access;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.document.DocumentAccess;
import lk.auradot.wms.document.document.DocumentEvents;
import lk.auradot.wms.document.document.DocumentKind;
import lk.auradot.wms.document.document.DocumentRow;
import lk.auradot.wms.document.document.DocumentService;
import lk.auradot.wms.document.document.DocumentStore;
import lk.auradot.wms.document.document.DocumentView;
import lk.auradot.wms.document.document.VersionNumbers;
import lk.auradot.wms.document.document.VersionRow;
import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.document.requests.InstanceAccess;
import lk.auradot.wms.document.requests.InstanceFolders;
import lk.auradot.wms.document.storage.ObjectStore;
import lk.auradot.wms.document.storage.StorageProperties;
import lk.auradot.wms.document.type.Metadata;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Spec §6 upload flow: a presigned PUT, then a confirm that checks size and SHA-256 before a version exists. */
@Service
public class UploadService {

    private static final Pattern MIME = Pattern.compile("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*");

    private final UploadStore uploads;
    private final DocumentStore documents;
    private final DocumentService documentService;
    private final Access access;
    private final Metadata metadata;
    private final ObjectStore objects;
    private final StorageProperties storage;
    private final UploadProperties props;
    private final Outbox outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DocumentAccess documentAccess;
    private final InstanceFolders instanceFolders;

    public UploadService(UploadStore uploads, DocumentStore documents, DocumentService documentService, Access access,
                         Metadata metadata, ObjectStore objects, StorageProperties storage, UploadProperties props,
                         Outbox outbox, TransactionTemplate tx, Clock clock, DocumentAccess documentAccess,
                         InstanceFolders instanceFolders) {
        this.uploads = uploads;
        this.documents = documents;
        this.documentService = documentService;
        this.access = access;
        this.metadata = metadata;
        this.objects = objects;
        this.storage = storage;
        this.props = props;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.documentAccess = documentAccess;
        this.instanceFolders = instanceFolders;
    }

    public UploadTicket start(StartUploadRequest request, Caller caller) {
        String fileName = Names.check(request.fileName());
        if (request.size() > storage.maxUploadSize()) {
            throw ApiException.badRequest("DOC_UPLOAD_TOO_LARGE", "Files can be at most " + storage.maxUploadSize() + " bytes");
        }
        String mime = mime(request.mimeType());
        boolean staged = Boolean.TRUE.equals(request.staged());
        int targets = (request.folderId() != null ? 1 : 0) + (request.documentId() != null ? 1 : 0)
                + (request.instanceId() != null ? 1 : 0) + (staged ? 1 : 0);
        if (targets != 1) {
            throw ApiException.badRequest("DOC_UPLOAD_TARGET", "Give exactly one of a folder (a new document), a request "
                    + "(an attachment), staged (a file for a form) or a document (a new version)");
        }
        String id = UUID.randomUUID().toString();
        String folderId = request.folderId();
        String name = null;
        Map<String, Object> values = null;
        if (request.documentId() != null) {
            DocumentRow document = documentService.visible(request.documentId(), caller);
            documentAccess.require(document, caller, Permission.WRITE);
            DocumentService.requireFile(document);
            DocumentService.requireHolder(document, caller);
        } else {
            if (staged) {
                folderId = instanceFolders.staging(id);
            } else if (request.instanceId() != null) {
                InstanceAccess instance = documentAccess.requireAttach(request.instanceId(), caller);
                folderId = instanceFolders.ensure(instance.processKey(), request.instanceId());
            } else {
                if (instanceFolders.isSystem(folderId)) {
                    throw ApiException.conflict("DOC_SYSTEM_FOLDER", "Request folders are managed by the system");
                }
                access.folder(folderId, caller, Permission.WRITE);
            }
            name = Names.check(request.name() == null ? fileName : request.name());
            if (documents.nameTaken(folderId, name)) {
                throw nameTaken();
            }
            values = metadata.check(request.typeId(), request.metadata(), caller);
        }
        Instant now = clock.instant();
        String key = "objects/" + id;
        Instant expiresAt = now.plus(storage.uploadExpiry());
        uploads.insert(new UploadRow(id, key, fileName, request.size(), request.sha256().toLowerCase(Locale.ROOT), mime,
                folderId, request.documentId(), name, request.documentId() == null ? request.typeId() : null, values,
                Boolean.TRUE.equals(request.major()), blankToNull(request.comment()), caller.userId(), now, expiresAt, null,
                request.instanceId()));
        String url = objects.presignUpload(key, request.size(), mime, storage.uploadExpiry()).toString();
        return new UploadTicket(id, url, "PUT", Map.of("Content-Type", mime), expiresAt);
    }

    public DocumentView confirm(String uploadId, Caller caller) {
        UploadRow upload = uploads.find(uploadId).filter(u -> u.createdBy().equals(caller.userId()))
                .orElseThrow(() -> ApiException.notFound("DOC_UPLOAD_NOT_FOUND", "No such upload"));
        if (upload.confirmedAt() != null) {
            throw confirmed();
        }
        if (!clock.instant().isBefore(upload.createdAt().plus(props.confirmWindow()))) {
            throw ApiException.conflict("DOC_UPLOAD_EXPIRED", "This upload waited too long; please upload the file again");
        }
        // workflow-service is remote: asked before the transaction, never while a document row is locked.
        DocumentAccess.RequestGrant grant = DocumentAccess.RequestGrant.NONE;
        if (upload.instanceId() != null) {
            documentAccess.requireAttach(upload.instanceId(), caller);
        } else if (upload.documentId() != null) {
            grant = documents.find(upload.documentId()).map(d -> documentAccess.viaRequest(d, caller))
                    .orElse(DocumentAccess.RequestGrant.NONE);
        }
        DocumentAccess.RequestGrant versionGrant = grant;
        String key = verifiedCopy(upload);
        String documentId;
        try {
            documentId = tx.execute(status -> upload.folderId() != null
                    ? createDocument(upload, key, caller) : addVersion(upload, key, caller, versionGrant));
        } catch (RuntimeException e) {
            objects.delete(key);
            throw e;
        }
        objects.delete(upload.s3Key());
        return documentService.get(documentId, caller);
    }

    /**
     * Copies the upload to a key of its own and checks that copy: the presigned URL stays valid for a while and
     * could otherwise replace the bytes of a confirmed version. The copy must have exactly the announced size and
     * SHA-256; a mismatch is removed at once, with the upload.
     */
    private String verifiedCopy(UploadRow upload) {
        if (objects.size(upload.s3Key()).isEmpty()) {
            throw missing();
        }
        String key = "versions/" + upload.id();
        try {
            objects.copy(upload.s3Key(), key);
        } catch (ObjectStore.MissingObjectException e) {
            throw missing();
        }
        Optional<Long> size = objects.size(key);
        if (size.isEmpty() || size.get() != upload.size() || !upload.sha256().equals(sha256(key))) {
            objects.delete(key);
            objects.delete(upload.s3Key());
            throw ApiException.badRequest("DOC_UPLOAD_MISMATCH", "The uploaded file is not the one announced; please upload it again");
        }
        return key;
    }

    private static ApiException missing() {
        return ApiException.conflict("DOC_UPLOAD_MISSING", "The file has not arrived yet; upload it, then confirm");
    }

    private String createDocument(UploadRow upload, String key, Caller caller) {
        if (upload.instanceId() == null && !instanceFolders.isStaging(upload.folderId())) {
            access.folder(upload.folderId(), caller, Permission.WRITE);
        }
        Instant now = clock.instant();
        Map<String, Object> values = upload.metadata() == null ? Map.of() : upload.metadata();
        DocumentRow document = new DocumentRow(UUID.randomUUID().toString(), upload.folderId(), upload.name(), upload.typeId(),
                values, null, null, null, upload.instanceId(), caller.userId(), now, now, DocumentKind.FILE);
        try {
            documents.insert(document, Metadata.searchText(upload.name(), values));
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        VersionRow version = version(upload, key, document.id(), new int[] {1, 0}, caller, now);
        documents.insertVersion(version);
        documents.setCurrent(document.id(), version.id(), now);
        finish(upload, document, version, now);
        return document.id();
    }

    private String addVersion(UploadRow upload, String key, Caller caller, DocumentAccess.RequestGrant grant) {
        DocumentRow document = documents.findForUpdate(upload.documentId()).orElseThrow(DocumentService::notFound);
        documentAccess.require(document, caller, Permission.WRITE, grant);
        DocumentService.requireFile(document);
        DocumentService.requireHolder(document, caller);
        Instant now = clock.instant();
        VersionRow version = version(upload, key, document.id(), VersionNumbers.next(documents.latest(document.id()), upload.majorVersion()),
                caller, now);
        documents.insertVersion(version);
        documents.setCurrent(document.id(), version.id(), now);
        // Checking in a version releases the check-out (spec §6).
        documents.setCheckout(document.id(), null, null);
        finish(upload, document, version, now);
        return document.id();
    }

    private void finish(UploadRow upload, DocumentRow document, VersionRow version, Instant now) {
        if (!uploads.markConfirmed(upload.id(), now)) {
            throw confirmed();
        }
        DocumentEvents.versionUploaded(outbox, document, version, now);
    }

    private static VersionRow version(UploadRow upload, String key, String documentId, int[] number, Caller caller, Instant now) {
        return new VersionRow(UUID.randomUUID().toString(), documentId, number[0], number[1], key, upload.size(),
                upload.mime(), upload.sha256(), upload.fileName(), caller.userId(), now, upload.comment());
    }

    private String sha256(String key) {
        try (InputStream in = objects.open(key)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (ObjectStore.MissingObjectException e) {
            throw ApiException.conflict("DOC_UPLOAD_MISSING", "The file has not arrived yet; upload it, then confirm");
        } catch (IOException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOC_STORAGE_UNAVAILABLE",
                    "The file could not be read from storage; please try again");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mime(String raw) {
        if (raw == null || raw.isBlank()) {
            return "application/octet-stream";
        }
        String mime = raw.strip().toLowerCase(Locale.ROOT);
        if (!MIME.matcher(mime.split(";", 2)[0].strip()).matches()) {
            throw ApiException.badRequest("DOC_MIME_INVALID", "The file type must look like type/subtype");
        }
        return mime;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static ApiException nameTaken() {
        return ApiException.conflict("DOC_NAME_TAKEN", "Something with this name already exists here");
    }

    private static ApiException confirmed() {
        return ApiException.conflict("DOC_UPLOAD_CONFIRMED", "This upload was already confirmed");
    }
}
