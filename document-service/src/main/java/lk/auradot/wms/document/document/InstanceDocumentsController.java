package lk.auradot.wms.document.document;

import lk.auradot.wms.document.access.Callers;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class InstanceDocumentsController {

    private final DocumentService documents;
    private final Callers callers;

    public InstanceDocumentsController(DocumentService documents, Callers callers) {
        this.documents = documents;
        this.callers = callers;
    }

    @GetMapping("/instances/{instanceId}/documents")
    public List<DocumentSummary> attachments(@PathVariable String instanceId, @AuthenticationPrincipal Jwt jwt) {
        return documents.attachments(instanceId, callers.of(jwt));
    }
}
