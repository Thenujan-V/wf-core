package lk.auradot.wms.document.staging;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StagedFilesController {

    private final StagedFiles staged;

    public StagedFilesController(StagedFiles staged) {
        this.staged = staged;
    }

    /** Spec §2.2 "form → document (verify file references)": for form-service, as a service account. */
    @PostMapping("/staged-files/verify")
    @PreAuthorize("hasRole('wms-service')")
    public VerifyResponse verify(@Valid @RequestBody VerifyRequest request) {
        return staged.verify(request.submittedBy(), request.documentIds());
    }
}
