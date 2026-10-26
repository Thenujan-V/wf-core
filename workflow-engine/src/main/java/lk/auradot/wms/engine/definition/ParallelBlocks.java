package lk.auradot.wms.engine.definition;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches each parallel split with the join where all its branches meet: the split's immediate
 * post-dominator. The branches between them must form a closed block.
 */
final class ParallelBlocks {

    private static final String EXIT = "\u0000exit";

    private ParallelBlocks() {
    }

    static Map<String, String> analyze(Graph graph, List<ValidationError> errors) {
        Map<String, Set<String>> postDominators = postDominators(graph);
        Map<String, String> joinBySplit = new LinkedHashMap<>();
        Map<String, String> splitByJoin = new HashMap<>();
        for (String split : graph.nodes.keySet()) {
            if (!graph.isSplit(split)) {
                continue;
            }
            String join = immediatePostDominator(split, postDominators);
            if (join == null || !graph.isJoin(join)) {
                errors.add(new ValidationError(split, "PARALLEL_NO_JOIN",
                        "The branches of this split never meet at a single parallel join"));
                continue;
            }
            Set<String> block = block(graph, split, join);
            boolean valid = true;
            if (block.contains(split)) {
                errors.add(new ValidationError(split, "PARALLEL_LOOP_THROUGH_SPLIT",
                        "A flow inside the parallel branches leads back to their split"));
                valid = false;
            }
            for (String node : block) {
                for (Edge edge : graph.in(node)) {
                    if (!block.contains(edge.source()) && !edge.source().equals(split)) {
                        errors.add(new ValidationError(node, "PARALLEL_BLOCK_ENTRY",
                                "A flow from outside the parallel branches enters them here"));
                        valid = false;
                    }
                }
            }
            for (Edge edge : graph.in(join)) {
                if (!block.contains(edge.source()) && !edge.source().equals(split)) {
                    errors.add(new ValidationError(join, "PARALLEL_JOIN_FOREIGN_INPUT",
                            "This join also receives flows that did not come from its split"));
                    valid = false;
                }
            }
            if (splitByJoin.putIfAbsent(join, split) != null) {
                errors.add(new ValidationError(join, "PARALLEL_JOIN_UNMATCHED", "This join is shared by more than one split"));
                valid = false;
            }
            if (valid) {
                joinBySplit.put(split, join);
            }
        }
        for (String node : graph.nodes.keySet()) {
            if (graph.isJoin(node) && !splitByJoin.containsKey(node)) {
                errors.add(new ValidationError(node, "PARALLEL_JOIN_UNMATCHED", "This join has no matching parallel split"));
            }
        }
        return joinBySplit;
    }

    /** Classic iterative data flow with a virtual exit after every end node. */
    private static Map<String, Set<String>> postDominators(Graph graph) {
        Set<String> all = new HashSet<>(graph.nodes.keySet());
        all.add(EXIT);
        Map<String, Set<String>> pd = new HashMap<>();
        for (String node : graph.nodes.keySet()) {
            pd.put(node, new HashSet<>(all));
        }
        pd.put(EXIT, Set.of(EXIT));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String node : graph.nodes.keySet()) {
                List<String> successors = graph.nodes.get(node) instanceof EndNode
                        ? List.of(EXIT)
                        : graph.out(node).stream().map(Edge::target).toList();
                Set<String> next = null;
                for (String successor : successors) {
                    if (next == null) {
                        next = new HashSet<>(pd.get(successor));
                    } else {
                        next.retainAll(pd.get(successor));
                    }
                }
                if (next == null) {
                    next = new HashSet<>();
                }
                next.add(node);
                if (!next.equals(pd.get(node))) {
                    pd.put(node, next);
                    changed = true;
                }
            }
        }
        return pd;
    }

    private static String immediatePostDominator(String node, Map<String, Set<String>> pd) {
        Set<String> strict = new HashSet<>(pd.get(node));
        strict.remove(node);
        for (String candidate : strict) {
            if (pd.get(candidate).equals(strict)) {
                return candidate;
            }
        }
        return null;
    }

    private static Set<String> block(Graph graph, String split, String join) {
        Set<String> block = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        for (Edge edge : graph.out(split)) {
            if (!edge.target().equals(join)) {
                queue.add(edge.target());
            }
        }
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (!block.add(node) || node.equals(split)) {
                continue;
            }
            for (Edge edge : graph.out(node)) {
                if (!edge.target().equals(join)) {
                    queue.add(edge.target());
                }
            }
        }
        return block;
    }
}
