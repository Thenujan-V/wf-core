package lk.auradot.wms.document.staging;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record VerifyRequest(@NotBlank String submittedBy, @NotNull @Size(max = 100) List<@NotBlank String> documentIds) {
}
