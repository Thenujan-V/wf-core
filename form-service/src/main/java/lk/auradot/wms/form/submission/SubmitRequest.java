package lk.auradot.wms.form.submission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lk.auradot.wms.form.model.Access;

import java.util.Map;

/** Sent by workflow-service on behalf of {@code submittedBy}; {@code contextRef} links the submission (e.g. a task). */
public record SubmitRequest(@NotBlank String formKey, @NotNull @Positive Integer version, @NotNull Map<String, Object> data,
                            Map<String, Access> access, Map<String, Object> baseline,
                            @NotBlank @Size(max = 255) String submittedBy, @Size(max = 255) String contextRef) {
}
