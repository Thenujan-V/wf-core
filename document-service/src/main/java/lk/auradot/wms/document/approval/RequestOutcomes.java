package lk.auradot.wms.document.approval;

import java.util.Optional;

/** Asks workflow-service how a request ended. Empty: it knows no such request. Throws when it cannot answer. */
public interface RequestOutcomes {

    Optional<RequestOutcome> outcome(String instanceId);
}
