package lk.auradot.wms.document.document;

import java.util.Optional;

/** Versions are major.minor: the first is 1.0, a check-in adds a minor version unless a major one is asked for. */
public final class VersionNumbers {

    private VersionNumbers() {
    }

    public static int[] next(Optional<VersionRow> latest, boolean major) {
        return latest.map(v -> major ? new int[] {v.major() + 1, 0} : new int[] {v.major(), v.minor() + 1})
                .orElse(new int[] {1, 0});
    }
}
