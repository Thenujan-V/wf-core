package lk.auradot.wms.document.folder;

import jakarta.validation.constraints.NotBlank;

public record RenameRequest(@NotBlank String name) {
}
