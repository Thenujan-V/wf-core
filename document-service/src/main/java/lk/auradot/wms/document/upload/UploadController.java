package lk.auradot.wms.document.upload;

import jakarta.validation.Valid;
import lk.auradot.wms.document.access.Callers;
import lk.auradot.wms.document.document.DocumentView;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UploadController {

    private final UploadService uploads;
    private final Callers callers;

    public UploadController(UploadService uploads, Callers callers) {
        this.uploads = uploads;
        this.callers = callers;
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public UploadTicket start(@Valid @RequestBody StartUploadRequest request, @AuthenticationPrincipal Jwt jwt) {
        return uploads.start(request, callers.of(jwt));
    }

    @PostMapping("/uploads/{id}/confirm")
    public DocumentView confirm(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return uploads.confirm(id, callers.of(jwt));
    }
}
