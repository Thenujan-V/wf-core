package lk.auradot.wms.workflow.definition;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record DraftRequest(@NotBlank @Size(max = 255) String name, @NotNull Map<String, Object> graph) {
}
