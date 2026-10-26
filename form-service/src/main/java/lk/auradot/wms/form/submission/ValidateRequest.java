package lk.auradot.wms.form.submission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lk.auradot.wms.form.model.Access;

import java.util.Map;

public record ValidateRequest(@NotBlank String formKey, @NotNull @Positive Integer version, @NotNull Map<String, Object> data,
                              Map<String, Access> access, Map<String, Object> baseline) {
}
