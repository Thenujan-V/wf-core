package lk.auradot.wms.form.definition;

import jakarta.validation.Valid;
import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.form.model.FieldError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/definitions")
public class FormDefinitionController {

    private static final String DESIGNER = "hasAnyRole('process-designer', 'wms-admin')";
    /** Library admins pick a published form for a document type's metadata (spec §6); they read, never edit. */
    private static final String DESIGNER_OR_LIBRARY_ADMIN = "hasAnyRole('process-designer', 'wms-admin', 'document-admin')";
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,99}");

    private final FormStore store;

    public FormDefinitionController(FormStore store) {
        this.store = store;
    }

    @GetMapping
    @PreAuthorize(DESIGNER_OR_LIBRARY_ADMIN)
    public List<FormSummary> list() {
        return store.list();
    }

    @GetMapping("/{key}/draft")
    @PreAuthorize(DESIGNER)
    public FormDraft draft(@PathVariable String key) {
        return store.draft(requireKey(key))
                .orElseThrow(() -> ApiException.notFound("FORM_DRAFT_NOT_FOUND", "There is no draft for this form"));
    }

    @PutMapping("/{key}/draft")
    @PreAuthorize(DESIGNER)
    public FormDraft saveDraft(@PathVariable String key, @Valid @RequestBody DraftRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        store.saveDraft(requireKey(key), request.name().trim(), request.definition(), jwt.getSubject());
        return store.draft(key).orElseThrow();
    }

    @PostMapping("/{key}/validate")
    @PreAuthorize(DESIGNER)
    public ValidationReport validate(@PathVariable String key) {
        List<FieldError> errors = store.validateDraft(requireKey(key));
        return new ValidationReport(errors.isEmpty(), errors);
    }

    @PostMapping("/{key}/publish")
    @PreAuthorize(DESIGNER)
    public ResponseEntity<PublishedForm> publish(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        StoredForm published = store.publish(requireKey(key), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PublishedForm.of(published));
    }

    @GetMapping("/{key}/versions")
    @PreAuthorize(DESIGNER_OR_LIBRARY_ADMIN)
    public List<VersionSummary> versions(@PathVariable String key) {
        return store.versions(requireKey(key));
    }

    /** Anyone signed in (people filling in a form, or workflow-service) may read a published version. */
    @GetMapping("/{key}/versions/{version}")
    public StoredForm version(@PathVariable String key, @PathVariable int version) {
        return store.version(requireKey(key), version)
                .orElseThrow(() -> ApiException.notFound("FORM_VERSION_NOT_FOUND", "Form '" + key + "' has no version " + version));
    }

    @PostMapping("/{key}/retire")
    @PreAuthorize(DESIGNER)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retire(@PathVariable String key) {
        store.retire(requireKey(key));
    }

    static String requireKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("FORM_KEY_INVALID", "Form keys use lowercase letters, digits and dashes (2-100 characters)");
        }
        return key;
    }
}
