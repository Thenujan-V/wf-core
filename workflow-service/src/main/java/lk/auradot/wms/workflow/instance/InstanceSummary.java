package lk.auradot.wms.workflow.instance;

import java.time.Instant;

public record InstanceSummary(String id, String processKey, int processVersion, String businessKey, String status,
                              String endStatus, String initiatorId, Instant startedAt, Instant endedAt) {
}
