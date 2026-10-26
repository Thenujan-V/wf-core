package lk.auradot.wms.document.written;

import jakarta.validation.Valid;
import lk.auradot.wms.document.access.Callers;
import lk.auradot.wms.document.document.ContentLink;
import lk.auradot.wms.document.document.DocumentView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WrittenDocumentController {

    private final WrittenDocumentService written;
    private final Callers callers;
    private final SubmissionService submissions;

    public WrittenDocumentController(WrittenDocumentService written, Callers callers, SubmissionService submissions) {
        this.submissions = submissions;
        this.written = written;
        this.callers = callers;
    }

    @PostMapping("/written")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentView create(@Valid @RequestBody CreateWrittenRequest request, @AuthenticationPrincipal Jwt jwt) {
        return written.create(request, callers.of(jwt));
    }

    @GetMapping("/documents/{id}/draft")
    public DraftView draft(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return written.draft(id, callers.of(jwt));
    }

    @GetMapping("/documents/{id}/review")
    public ContentLink review(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return submissions.review(id, callers.of(jwt));
    }

    @PostMapping("/documents/{id}/approval/release")
    public DocumentView releaseFromApproval(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return submissions.release(id, callers.of(jwt));
    }

    @PutMapping("/documents/{id}/draft")
    public DraftView save(@PathVariable String id, @Valid @RequestBody SaveDraftRequest request, @AuthenticationPrincipal Jwt jwt) {
        return written.save(id, request, callers.of(jwt));
    }
}
