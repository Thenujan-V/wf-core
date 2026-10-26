package lk.auradot.wms.document.type;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lk.auradot.wms.document.forms.FormRef;

public record TypeRequest(@NotBlank String name, @Valid FormRef form) {
}
