package lk.auradot.wms.document.forms;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** A published form version in form-service. */
public record FormRef(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,99}") String key, @Positive int version) {
}
