package lk.auradot.wms.document.document;

import jakarta.validation.Valid;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.Callers;
import lk.auradot.wms.document.written.SubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DocumentController {

    private final DocumentService documents;
    private final Callers callers;
    private final SubmissionService submissions;

    public DocumentController(DocumentService documents, Callers callers, SubmissionService submissions) {
        this.submissions = submissions;
        this.documents = documents;
        this.callers = callers;
    }

    @GetMapping("/folders/{folderId}/documents")
    public List<DocumentSummary> list(@PathVariable String folderId, @AuthenticationPrincipal Jwt jwt) {
        return documents.list(folderId, callers.of(jwt));
    }

    @GetMapping("/documents/{id}")
    public DocumentView get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return documents.get(id, callers.of(jwt));
    }

    @PatchMapping("/documents/{id}")
    public DocumentView rename(@PathVariable String id, @Valid @RequestBody RenameDocumentRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return documents.rename(id, request, callers.of(jwt));
    }

    @PutMapping("/documents/{id}/metadata")
    public DocumentView setMetadata(@PathVariable String id, @RequestBody MetadataRequest request, @AuthenticationPrincipal Jwt jwt) {
        return documents.setMetadata(id, request, callers.of(jwt));
    }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        documents.delete(id, callers.of(jwt));
    }

    @PostMapping("/documents/{id}/checkout")
    public DocumentView checkout(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return documents.checkout(id, callers.of(jwt));
    }

    @PostMapping("/documents/{id}/checkout/cancel")
    public DocumentView cancelCheckout(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return documents.cancelCheckout(id, callers.of(jwt));
    }

    @GetMapping("/documents/{id}/versions/{versionId}/content")
    public ContentLink content(@PathVariable String id, @PathVariable String versionId,
                               @RequestParam(defaultValue = "false") boolean inline, @AuthenticationPrincipal Jwt jwt) {
        return documents.content(id, versionId, inline, callers.of(jwt));
    }

    @PostMapping("/documents/{id}/versions/{versionId}/restore")
    public DocumentView restore(@PathVariable String id, @PathVariable String versionId,
                                @Valid @RequestBody(required = false) RestoreRequest request, @AuthenticationPrincipal Jwt jwt) {
        return documents.restore(id, versionId, request, callers.of(jwt));
    }

    @PostMapping("/documents/{id}/link")
    public DocumentView link(@PathVariable String id, @Valid @RequestBody LinkRequest request, @AuthenticationPrincipal Jwt jwt) {
        Caller caller = callers.of(jwt);
        String instanceId = request.instanceId().strip();
        // An uploaded file is attached to the request; a written document is sent to it, which freezes its text.
        return documents.visible(id, caller).kind() == DocumentKind.WRITTEN
                ? submissions.send(id, instanceId, caller)
                : documents.link(id, instanceId, caller);
    }
}
