package lk.auradot.wms.document.type;

import jakarta.validation.Valid;
import lk.auradot.wms.document.access.Callers;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/document-types")
public class DocumentTypeController {

    private static final String LIBRARY_ADMIN = "hasAnyRole('document-admin', 'wms-admin')";

    private final DocumentTypeService types;
    private final Callers callers;

    public DocumentTypeController(DocumentTypeService types, Callers callers) {
        this.types = types;
        this.callers = callers;
    }

    @GetMapping
    public List<DocumentType> list() {
        return types.list();
    }

    @PostMapping
    @PreAuthorize(LIBRARY_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentType create(@Valid @RequestBody TypeRequest request, @AuthenticationPrincipal Jwt jwt) {
        return types.create(request, callers.of(jwt));
    }

    @PutMapping("/{id}")
    @PreAuthorize(LIBRARY_ADMIN)
    public DocumentType update(@PathVariable String id, @Valid @RequestBody TypeRequest request, @AuthenticationPrincipal Jwt jwt) {
        return types.update(id, request, callers.of(jwt));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(LIBRARY_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        types.delete(id);
    }
}
