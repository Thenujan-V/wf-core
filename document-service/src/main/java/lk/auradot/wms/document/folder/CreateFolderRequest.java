package lk.auradot.wms.document.folder;

import jakarta.validation.constraints.NotBlank;

public record CreateFolderRequest(@NotBlank String parentId, @NotBlank String name) {
}
