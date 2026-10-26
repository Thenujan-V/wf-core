package lk.auradot.wms.document.document;

import jakarta.validation.constraints.NotBlank;

public record RenameDocumentRequest(@NotBlank String name) {
}
