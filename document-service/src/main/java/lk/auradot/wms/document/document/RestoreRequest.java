package lk.auradot.wms.document.document;

import jakarta.validation.constraints.Size;

public record RestoreRequest(@Size(max = 1000) String comment) {
}
