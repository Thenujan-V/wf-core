package lk.auradot.wms.engine.definition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Publish validation (spec §4.1). Returns every problem found so the designer can highlight them all. */
public final class DefinitionValidator {

    private DefinitionValidator() {
    }

    public static List<ValidationError> validate(ProcessDefinition definition) {
        List<ValidationError> errors = new ArrayList<>();
        Graph graph = Graph.build(definition, errors);
        if (!errors.isEmpty()) {
            return distinct(errors);
        }
        checkStartAndEnd(graph, errors);
        if (!errors.isEmpty()) {
            return distinct(errors);
        }
        for (Node node : graph.nodes.values()) {
            NodeRules.check(node, graph, errors);
        }
        if (!errors.isEmpty()) {
            return distinct(errors);
        }
        checkReachability(graph, errors);
        if (!errors.isEmpty()) {
            return distinct(errors);
        }
        NodeRules.checkCyclesHaveWaitStates(graph, errors);
        ParallelBlocks.analyze(graph, errors);
        return distinct(errors);
    }

    private static void checkStartAndEnd(Graph graph, List<ValidationError> errors) {
        long starts = graph.nodes.values().stream().filter(StartNode.class::isInstance).count();
        if (starts != 1) {
            errors.add(new ValidationError(null, "START_COUNT", "A process needs exactly one start node"));
        }
        if (graph.nodes.values().stream().noneMatch(EndNode.class::isInstance)) {
            errors.add(new ValidationError(null, "NO_END", "A process needs at least one end node"));
        }
    }

    private static void checkReachability(Graph graph, List<ValidationError> errors) {
        String start = graph.nodes.values().stream().filter(StartNode.class::isInstance).findFirst().orElseThrow().id();
        Set<String> reachable = walk(List.of(start), id -> graph.out(id).stream().map(Edge::target).toList());
        List<String> ends = graph.nodes.values().stream().filter(EndNode.class::isInstance).map(Node::id).toList();
        Set<String> canEnd = walk(ends, id -> graph.in(id).stream().map(Edge::source).toList());
        for (String id : graph.nodes.keySet()) {
            if (!reachable.contains(id)) {
                errors.add(new ValidationError(id, "UNREACHABLE", "This node cannot be reached from the start"));
            } else if (!canEnd.contains(id)) {
                errors.add(new ValidationError(id, "DEAD_END", "No path from this node reaches an end"));
            }
        }
    }

    static Set<String> walk(List<String> from, Function<String, List<String>> next) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(from);
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (seen.add(id)) {
                queue.addAll(next.apply(id));
            }
        }
        return seen;
    }

    private static List<ValidationError> distinct(List<ValidationError> errors) {
        return errors.stream().distinct().toList();
    }
}
