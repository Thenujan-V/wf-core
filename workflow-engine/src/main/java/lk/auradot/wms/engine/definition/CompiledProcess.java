package lk.auradot.wms.engine.definition;

import java.util.List;
import java.util.Map;

/** A validated definition plus the indexes the runtime needs. Obtain one via {@link ProcessCompiler}. */
public final class CompiledProcess {

    private final ProcessDefinition definition;
    private final Graph graph;
    private final Map<String, String> joinBySplit;
    private final StartNode start;

    CompiledProcess(ProcessDefinition definition, Graph graph, Map<String, String> joinBySplit) {
        this.definition = definition;
        this.graph = graph;
        this.joinBySplit = Map.copyOf(joinBySplit);
        this.start = graph.nodes.values().stream().filter(StartNode.class::isInstance)
                .map(StartNode.class::cast).findFirst().orElseThrow();
    }

    public ProcessDefinition definition() {
        return definition;
    }

    public Node node(String id) {
        Node node = graph.nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node " + id);
        }
        return node;
    }

    public StartNode start() {
        return start;
    }

    public List<Edge> outgoing(String nodeId) {
        return graph.out(nodeId);
    }

    public boolean isSplit(String nodeId) {
        return graph.isSplit(nodeId);
    }

    public boolean isJoin(String nodeId) {
        return graph.isJoin(nodeId);
    }

    public String joinFor(String splitId) {
        return joinBySplit.get(splitId);
    }
}
