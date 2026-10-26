package lk.auradot.wms.engine.definition;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-node rules, and the rule that every loop must pause somewhere. */
final class NodeRules {

    private NodeRules() {
    }

    static void check(Node node, Graph graph, List<ValidationError> errors) {
        List<Edge> out = graph.out(node.id());
        List<Edge> in = graph.in(node.id());
        for (Edge edge : out) {
            if (!(node instanceof ExclusiveGatewayNode) && (edge.condition() != null || edge.defaultFlow())) {
                errors.add(error(node, "CONDITION_NOT_ALLOWED",
                        "Only exclusive gateways can have conditional or default flows"));
            }
            if (!(node instanceof UserTaskNode) && edge.outcome() != null) {
                errors.add(error(node, "OUTCOME_NOT_ALLOWED", "Only user tasks can have outcome flows"));
            }
        }
        switch (node) {
            case StartNode start -> {
                if (!in.isEmpty()) {
                    errors.add(error(start, "START_INCOMING", "Nothing may flow into the start node"));
                }
                requireSingleOutgoing(start, out, errors);
            }
            case EndNode end -> {
                if (!out.isEmpty()) {
                    errors.add(error(end, "END_OUTGOING", "Nothing may flow out of an end node"));
                }
            }
            case UserTaskNode task -> checkUserTask(task, out, errors);
            case ExclusiveGatewayNode gateway -> checkExclusive(gateway, out, errors);
            case ParallelGatewayNode parallel -> {
                boolean split = in.size() == 1 && out.size() > 1;
                boolean join = in.size() > 1 && out.size() == 1;
                if (!split && !join) {
                    errors.add(error(parallel, "PARALLEL_SHAPE", "A parallel gateway must either split "
                            + "(one incoming, several outgoing flows) or join (several incoming, one outgoing flow)"));
                }
            }
            case TimerNode timer -> {
                checkTimer(timer, errors);
                requireSingleOutgoing(timer, out, errors);
            }
            case SystemActionNode action -> {
                checkAction(action, errors);
                requireSingleOutgoing(action, out, errors);
            }
        }
    }

    private static void checkUserTask(UserTaskNode task, List<Edge> out, List<ValidationError> errors) {
        checkAssignment(task, errors);
        List<String> outcomes = task.outcomes() == null ? List.of() : task.outcomes();
        if (outcomes.isEmpty()) {
            errors.add(error(task, "TASK_NO_OUTCOMES", "A task needs at least one outcome"));
        }
        Set<String> seen = new HashSet<>();
        for (String outcome : outcomes) {
            if (outcome == null || outcome.isBlank()) {
                errors.add(error(task, "TASK_OUTCOME_INVALID", "Outcomes must have a name"));
            } else if (!seen.add(outcome)) {
                errors.add(error(task, "TASK_DUPLICATE_OUTCOME", "Outcome '" + outcome + "' is listed twice"));
            }
        }
        Map<String, Integer> flowsPerOutcome = new HashMap<>();
        for (Edge edge : out) {
            if (edge.outcome() == null) {
                errors.add(error(task, "TASK_EDGE_WITHOUT_OUTCOME", "Every flow out of a task must carry an outcome"));
            } else if (!outcomes.contains(edge.outcome())) {
                errors.add(error(task, "TASK_UNKNOWN_OUTCOME", "Flow uses unknown outcome '" + edge.outcome() + "'"));
            } else if (flowsPerOutcome.merge(edge.outcome(), 1, Integer::sum) > 1) {
                errors.add(error(task, "TASK_DUPLICATE_OUTCOME_EDGE",
                        "Outcome '" + edge.outcome() + "' has more than one flow"));
            }
        }
        for (String outcome : seen) {
            if (!flowsPerOutcome.containsKey(outcome)) {
                errors.add(error(task, "TASK_OUTCOME_WITHOUT_EDGE", "Outcome '" + outcome + "' has no flow"));
            }
        }
        checkApprovalMode(task, outcomes, errors);
        if (task.sla() != null) {
            checkSla(task, outcomes, errors);
        }
    }

    private static void checkAssignment(UserTaskNode task, List<ValidationError> errors) {
        boolean valid = switch (task.assignment()) {
            case null -> {
                errors.add(error(task, "TASK_NO_ASSIGNMENT", "A task needs an assignment"));
                yield true;
            }
            case Assignment.User user -> notBlank(user.userId());
            case Assignment.Group group -> notBlank(group.groupId());
            case Assignment.Role role -> notBlank(role.role());
            case Assignment.InitiatorManager manager -> manager.levels() >= 1 && manager.levels() <= 10;
            case Assignment.VariableUser variable -> validPath(variable.variable());
        };
        if (!valid) {
            errors.add(error(task, "TASK_ASSIGNMENT_INVALID",
                    "The assignment is incomplete (manager levels must be 1-10)"));
        }
    }

    private static void checkApprovalMode(UserTaskNode task, List<String> outcomes, List<ValidationError> errors) {
        ApprovalMode mode = task.approvalMode();
        if (mode == null) {
            errors.add(error(task, "TASK_MODE_MISSING", "Choose how many approvers must act"));
            return;
        }
        if (mode != ApprovalMode.SINGLE && task.assignment() != null && task.assignment().isSinglePerson()) {
            errors.add(error(task, "TASK_MODE_NEEDS_GROUP", "Multi-approver modes need a group or role assignment"));
        }
        if ((mode == ApprovalMode.ALL || mode == ApprovalMode.PERCENT)
                && !(outcomes.contains(Outcomes.APPROVE) && outcomes.contains(Outcomes.REJECT))) {
            errors.add(error(task, "TASK_MODE_NEEDS_APPROVE_REJECT", mode + " approval needs APPROVE and REJECT outcomes"));
        }
        boolean percentValid = mode == ApprovalMode.PERCENT
                ? task.percent() != null && task.percent() >= 1 && task.percent() <= 100
                : task.percent() == null;
        if (!percentValid) {
            errors.add(error(task, "TASK_PERCENT_RANGE", "A percentage (1-100) is set only for PERCENT approval"));
        }
    }

    private static void checkSla(UserTaskNode task, List<String> outcomes, List<ValidationError> errors) {
        Sla sla = task.sla();
        Duration dueIn = sla.dueIn();
        boolean dueValid = dueIn != null && dueIn.isPositive()
                && (!sla.businessDays() || dueIn.equals(Duration.ofDays(dueIn.toDays())));
        if (!dueValid) {
            errors.add(error(task, "SLA_DUE_IN", "The due time must be positive (whole days for business-day SLAs)"));
        }
        if (sla.reminderPercent() != null && (sla.reminderPercent() < 1 || sla.reminderPercent() > 99)) {
            errors.add(error(task, "SLA_REMINDER_RANGE", "The reminder must be at 1-99% of the due time"));
        }
        if (sla.onBreach() == null) {
            errors.add(error(task, "SLA_ACTION_MISSING", "Choose what happens when the task is overdue"));
        } else if (sla.onBreach() == BreachAction.AUTO_COMPLETE
                ? !outcomes.contains(sla.autoOutcome())
                : sla.autoOutcome() != null) {
            errors.add(error(task, "SLA_AUTO_OUTCOME",
                    "Automatic completion needs one of the task's outcomes; other actions take none"));
        }
    }

    private static void checkExclusive(ExclusiveGatewayNode gateway, List<Edge> out, List<ValidationError> errors) {
        if (out.isEmpty()) {
            errors.add(error(gateway, "EXCLUSIVE_OUTGOING", "An exclusive gateway needs outgoing flows"));
            return;
        }
        long defaults = out.stream().filter(Edge::defaultFlow).count();
        if (defaults == 0) {
            errors.add(error(gateway, "EXCLUSIVE_NO_DEFAULT", "An exclusive gateway needs a default flow"));
        } else if (defaults > 1) {
            errors.add(error(gateway, "EXCLUSIVE_MULTIPLE_DEFAULTS", "Only one flow can be the default"));
        }
        for (Edge edge : out) {
            if (edge.defaultFlow() && edge.condition() != null) {
                errors.add(error(gateway, "EXCLUSIVE_DEFAULT_HAS_CONDITION", "The default flow cannot have a condition"));
            }
            if (!edge.defaultFlow() && edge.condition() == null) {
                errors.add(error(gateway, "EXCLUSIVE_MISSING_CONDITION", "Every non-default flow needs a condition"));
            }
        }
    }

    private static void checkTimer(TimerNode timer, List<ValidationError> errors) {
        boolean hasDuration = timer.duration() != null;
        boolean hasVariable = timer.dueDateVariable() != null && !timer.dueDateVariable().isBlank();
        if (hasDuration == hasVariable || (hasDuration && !timer.duration().isPositive())
                || (hasVariable && !validPath(timer.dueDateVariable()))) {
            errors.add(error(timer, "TIMER_SPEC", "Set either a positive duration or a date variable"));
        }
    }

    private static void checkAction(SystemActionNode action, List<ValidationError> errors) {
        Map<String, Object> config = action.config();
        boolean valid = switch (action.type()) {
            case null -> false;
            case SEND_EMAIL -> notBlank(config.get("to")) && notBlank(config.get("template"));
            case SET_VARIABLE -> notBlank(config.get("variable")) && validPath((String) config.get("variable"))
                    && config.containsKey("value");
            case WEBHOOK -> isHttpUrl(config.get("url"));
        };
        if (!valid) {
            errors.add(error(action, "ACTION_CONFIG", switch (action.type()) {
                case null -> "Choose an action type";
                case SEND_EMAIL -> "An email needs 'to' and 'template'";
                case SET_VARIABLE -> "Set 'variable' (a dotted name) and 'value'";
                case WEBHOOK -> "A webhook needs an http(s) 'url'";
            }));
        }
    }

    /** Every cycle must contain a user task, timer or system action; otherwise the engine would spin. */
    static void checkCyclesHaveWaitStates(Graph graph, List<ValidationError> errors) {
        Map<String, Integer> state = new HashMap<>(); // 1 = on stack, 2 = done
        for (Node node : graph.nodes.values()) {
            if (!node.isWaitState()) {
                findCycle(node.id(), graph, state, errors);
            }
        }
    }

    private static void findCycle(String id, Graph graph, Map<String, Integer> state, List<ValidationError> errors) {
        if (state.containsKey(id)) {
            return;
        }
        state.put(id, 1);
        for (Edge edge : graph.out(id)) {
            Node target = graph.nodes.get(edge.target());
            if (target.isWaitState()) {
                continue;
            }
            Integer targetState = state.get(target.id());
            if (targetState == null) {
                findCycle(target.id(), graph, state, errors);
            } else if (targetState == 1) {
                errors.add(error(target, "CYCLE_WITHOUT_WAIT",
                        "This loop has no user task, timer or system action, so it would never pause"));
            }
        }
        state.put(id, 2);
    }

    static void requireSingleOutgoing(Node node, List<Edge> out, List<ValidationError> errors) {
        if (out.size() != 1) {
            errors.add(error(node, "NODE_OUTGOING", "This node must have exactly one outgoing flow"));
        }
    }

    static ValidationError error(Node node, String code, String message) {
        return new ValidationError(node.id(), code, message);
    }

    private static boolean notBlank(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static boolean validPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String segment : path.split("\\.", -1)) {
            if (segment.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHttpUrl(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        try {
            URI uri = URI.create(text);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
