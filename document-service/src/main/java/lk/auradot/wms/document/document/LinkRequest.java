package lk.auradot.wms.document.document;

import jakarta.validation.constraints.NotBlank;

public record LinkRequest(@NotBlank String instanceId) {
}
