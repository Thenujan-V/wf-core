package lk.auradot.wms.document.written;

import java.time.Instant;
import java.util.List;

/**
 * What an approved document says about its own approval (written-documents spec §5). Every value is printed as text.
 * {@code processName} and {@code endedAt} may be missing; the page then leaves that line out.
 */
public record ApprovalRecord(String documentName, String version, String reference, String processName, String sentBy,
                             Instant sentAt, List<Step> steps, Instant endedAt) {

    public record Step(String name, String person, String decision, Instant at) {
    }

    public ApprovalRecord {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
