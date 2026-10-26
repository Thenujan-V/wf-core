package lk.auradot.wms.engine.definition;

import java.util.List;

/**
 * Work for people. Each outgoing flow carries one of {@code outcomes}. {@code percent} is only used with
 * {@link ApprovalMode#PERCENT}; {@code sla} may be null.
 */
public record UserTaskNode(String id, String name, Assignment assignment, List<String> outcomes,
                           ApprovalMode approvalMode, Integer percent, Sla sla) implements Node {
    public UserTaskNode {
        outcomes = outcomes == null ? null : List.copyOf(outcomes);
    }
}
