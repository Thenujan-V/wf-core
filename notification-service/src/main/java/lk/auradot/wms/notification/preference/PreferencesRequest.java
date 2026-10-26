package lk.auradot.wms.notification.preference;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lk.auradot.wms.notification.plan.Kind;

import java.util.List;

/** The person's choices; kinds that are left out go back to their defaults. */
public record PreferencesRequest(@NotNull @Size(max = 50) List<@Valid @NotNull Choice> preferences) {

    /** Wrapper types: a missing value must be a validation error, not a silent "false". */
    public record Choice(@NotNull Kind kind, @NotNull Boolean inApp, @NotNull Boolean email) {
    }
}
