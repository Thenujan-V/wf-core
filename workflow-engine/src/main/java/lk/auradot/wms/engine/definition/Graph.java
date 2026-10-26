package lk.auradot.wms.engine.definition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Node and flow indexes for validation and execution. Flow order is definition order. */
final class Graph {

    final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, List<Edge>> outgoing = new HashMap<>();
    private final Map<String, List<Edge>> incoming = new HashMap<>();

    static Graph build(ProcessDefinition definition, List<ValidationError> errors) {
        Graph graph = new Graph();
        for (Node node : definition.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add(new ValidationError(null, "NODE_ID_MISSING", "Every node needs an id"));
            } else if (graph.nodes.putIfAbsent(node.id(), node) != null) {
                errors.add(new ValidationError(node.id(), "DUPLICATE_NODE_ID",
                        "Node id '" + node.id() + "' is used more than once"));
            }
        }
        Set<String> edgeIds = new HashSet<>();
        for (Edge edge : definition.edges()) {
            if (!edgeIds.add(edge.id())) {
                errors.add(new ValidationError(edge.source(), "DUPLICATE_EDGE_ID",
                        "Flow id '" + edge.id() + "' is used more than once"));
            }
            if (!graph.nodes.containsKey(edge.source()) || !graph.nodes.containsKey(edge.target())) {
                errors.add(new ValidationError(edge.source(), "EDGE_UNKNOWN_NODE",
                        "Flow '" + edge.id() + "' connects a node that does not exist"));
                continue;
            }
            graph.outgoing.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge);
            graph.incoming.computeIfAbsent(edge.target(), k -> new ArrayList<>()).add(edge);
        }
        return graph;
    }

    List<Edge> out(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    List<Edge> in(String nodeId) {
        return incoming.getOrDefault(nodeId, List.of());
    }

    boolean isSplit(String nodeId) {
        return nodes.get(nodeId) instanceof ParallelGatewayNode && out(nodeId).size() > 1;
    }

    boolean isJoin(String nodeId) {
        return nodes.get(nodeId) instanceof ParallelGatewayNode && in(nodeId).size() > 1;
    }
}
