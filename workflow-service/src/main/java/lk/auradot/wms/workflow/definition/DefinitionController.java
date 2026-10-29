package lk.auradot.wms.workflow.definition;

import jakarta.validation.Valid;
import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.definition.ValidationError;
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
@PreAuthorize("hasAnyRole('process-designer', 'wms-admin')")
public class DefinitionController {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,99}");

    private final DefinitionStore store;

    public DefinitionController(DefinitionStore store) {
        this.store = store;
    }

    @GetMapping
    public List<DefinitionSummary> list() {
        return store.list();
    }

    @GetMapping("/{key}/draft")
    public Draft draft(@PathVariable String key) {
        return store.draft(requireKey(key))
                .orElseThrow(() -> ApiException.notFound("WF_DRAFT_NOT_FOUND", "There is no draft for this process"));
    }

    @PutMapping("/{key}/draft")
    public Draft saveDraft(@PathVariable String key, @Valid @RequestBody DraftRequest request,
                           @AuthenticationPrincipal Jwt jwt) {
        store.saveDraft(requireKey(key), request.name().trim(), request.graph(), jwt.getSubject());
        return store.draft(key).orElseThrow();
    }

    @PostMapping("/{key}/validate")
    public ValidationReport validate(@PathVariable String key) {
        List<ValidationError> errors = store.validateDraft(requireKey(key));
        return new ValidationReport(errors.isEmpty(), errors);
    }

    @PostMapping("/{key}/publish")
    public ResponseEntity<PublishedView> publish(@PathVariable String key, @AuthenticationPrincipal Jwt jwt) {
        StoredDefinition published = store.publish(requireKey(key), jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED).body(PublishedView.of(published));
    }

    @GetMapping("/{key}/versions/{version}")
    public StoredDefinition version(@PathVariable String key, @PathVariable int version) {
        return store.version(requireKey(key), version)
                .orElseThrow(() -> ApiException.notFound("WF_VERSION_NOT_FOUND", "No such version"));
    }

    @PostMapping("/{key}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retire(@PathVariable String key) {
        store.retire(requireKey(key));
    }

    static String requireKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("WF_KEY_INVALID",
                    "Process keys use lowercase letters, digits and dashes (2-100 characters)");
        }
        return key;
    }
}
