package lk.auradot.wms.engine.definition;

import lk.auradot.wms.expression.Condition;

/**
 * A flow between nodes. {@code condition} and {@code defaultFlow} are only valid on exclusive-gateway flows;
 * {@code outcome} only on user-task flows.
 */
public record Edge(String id, String source, String target, Condition condition, boolean defaultFlow, String outcome) {
}
