package lk.auradot.wms.engine.definition;

import java.util.List;

/** An immutable process version: nodes plus directed flows (spec §4.1). */
public record ProcessDefinition(String key, int version, String name, List<Node> nodes, List<Edge> edges) {
    public ProcessDefinition {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
