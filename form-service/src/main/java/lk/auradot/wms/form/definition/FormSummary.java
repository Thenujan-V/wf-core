package lk.auradot.wms.form.definition;

import java.time.Instant;

/** One row of the designer's form list: the latest published version (if any) and whether a draft exists. */
public record FormSummary(String key, String name, Integer latestVersion, String status, boolean hasDraft,
                          Instant draftUpdatedAt) {
}
