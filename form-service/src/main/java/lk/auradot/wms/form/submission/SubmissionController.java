package lk.auradot.wms.form.submission;

import jakarta.validation.Valid;
import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.form.definition.CompiledForms;
import lk.auradot.wms.form.files.FileReferences;
import lk.auradot.wms.form.files.FilesUnavailableException;
import lk.auradot.wms.form.model.Access;
import lk.auradot.wms.form.model.FieldError;
import lk.auradot.wms.form.model.FormDefinition;
import lk.auradot.wms.form.model.SubmissionValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
public class SubmissionController {

    private final CompiledForms forms;
    private final SubmissionStore submissions;
    private final FileReferences files;
    private final Clock clock;

    public SubmissionController(CompiledForms forms, SubmissionStore submissions, FileReferences files, Clock clock) {
        this.forms = forms;
        this.submissions = submissions;
        this.files = files;
        this.clock = clock;
    }

    /** Dry run for instant feedback; the stored result always comes from POST /submissions. */
    @PostMapping("/validate")
    public ValidateResponse validate(@Valid @RequestBody ValidateRequest request) {
        var result = SubmissionValidator.validate(forms.get(request.formKey(), request.version()), request.data(),
                access(request.access()), baseline(request.baseline()));
        return new ValidateResponse(result.valid(), result.errors(), result.data());
    }

    @PostMapping("/submissions")
    @PreAuthorize("hasRole('wms-service')")
    public ResponseEntity<Submission> submit(@Valid @RequestBody SubmitRequest request) {
        FormDefinition form = forms.get(request.formKey(), request.version());
        Map<String, Object> baseline = baseline(request.baseline());
        var result = SubmissionValidator.validate(form, request.data(), access(request.access()), baseline);
        List<FieldError> errors = result.errors();
        Map<String, Object> data = result.data();
        if (result.valid()) {
            FileReferences.Result checked = checkFiles(form, data, baseline, request.submittedBy());
            errors = checked.errors();
            data = checked.data();
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("FORM_VALIDATION_FAILED",
                    "The form has " + errors.size() + " problem(s)").with("errors", errors);
        }
        Submission submission = new Submission(UUID.randomUUID().toString(), request.formKey(), request.version(),
                data, request.submittedBy(), clock.instant(), request.contextRef());
        submissions.insert(submission);
        return ResponseEntity.status(HttpStatus.CREATED).body(submission);
    }

    /** Spec §2.2 "form → document (verify file references)"; the dry run (/validate) does not ask. */
    private FileReferences.Result checkFiles(FormDefinition form, Map<String, Object> data, Map<String, Object> baseline,
                                             String submittedBy) {
        try {
            return files.check(form, data, baseline, submittedBy);
        } catch (FilesUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FORM_FILES_UNAVAILABLE",
                    "Attached files cannot be checked right now; please try again shortly");
        }
    }

    @GetMapping("/submissions/{id}")
    @PreAuthorize("hasAnyRole('wms-service', 'wms-admin')")
    public Submission submission(@PathVariable String id) {
        return submissions.find(id)
                .orElseThrow(() -> ApiException.notFound("FORM_SUBMISSION_NOT_FOUND", "No submission " + id));
    }

    private static Map<String, Access> access(Map<String, Access> access) {
        return Objects.requireNonNullElse(access, Map.of());
    }

    private static Map<String, Object> baseline(Map<String, Object> baseline) {
        return Objects.requireNonNullElse(baseline, Map.of());
    }
}
