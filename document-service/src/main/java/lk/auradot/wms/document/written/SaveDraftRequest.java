package lk.auradot.wms.document.written;

import jakarta.validation.constraints.NotNull;

/** {@code revision} is the one the browser last read or was given back by a save. */
public record SaveDraftRequest(@NotNull String html, @NotNull Integer revision) {
}
