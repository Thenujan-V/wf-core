package lk.auradot.wms.document.folder;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.access.PrincipalType;

import java.util.List;

public record AclRequest(boolean inheritAcl, @NotNull @Size(max = 200) List<@Valid Entry> entries) {

    public record Entry(@NotNull PrincipalType principalType, @NotBlank @Size(max = 255) String principalId,
                        @NotNull Permission permission) {
    }
}
