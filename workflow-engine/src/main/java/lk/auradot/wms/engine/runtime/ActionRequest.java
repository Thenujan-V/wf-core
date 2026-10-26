package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ActionType;

import java.util.Map;

/**
 * An email or webhook to perform outside any engine command (and outside the database transaction), so that
 * retried commands never repeat the side effect. {@code variables} is a read-only snapshot.
 */
public record ActionRequest(String jobId, String nodeId, ActionType type, Map<String, Object> config,
                            Map<String, Object> variables) {
}
