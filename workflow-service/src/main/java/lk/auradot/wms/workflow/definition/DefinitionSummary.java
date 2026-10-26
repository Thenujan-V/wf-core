package lk.auradot.wms.workflow.definition;

import java.time.Instant;

/** One row of the designer's process list: the latest published version (if any) and whether a draft exists. */
public record DefinitionSummary(String key, String name, Integer latestVersion, String status, boolean hasDraft,
                                Instant draftUpdatedAt) {
}
