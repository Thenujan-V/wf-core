package lk.auradot.wms.document.written;

import jakarta.validation.constraints.NotBlank;

public record CreateWrittenRequest(@NotBlank String folderId, @NotBlank String name) {
}
