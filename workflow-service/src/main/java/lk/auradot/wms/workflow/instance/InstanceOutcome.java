package lk.auradot.wms.workflow.instance;

import java.time.Instant;
import java.util.List;

/**
 * How a request ended and who decided each of its steps, for other services (document-service prints it on an approved
 * document). {@code endStatus} and {@code endedAt} are null while it runs; {@code steps} are the completed tasks, oldest first.
 */
public record InstanceOutcome(String id, String processKey, String processName, int processVersion, String businessKey,
                              String status, String endStatus, String initiatorId, Instant startedAt, Instant endedAt,
                              List<Step> steps) {

    public record Step(String name, String nodeId, String completedBy, String outcome, Instant completedAt) {
    }
}
