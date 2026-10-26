package lk.auradot.wms.engine.definition;

import lk.auradot.wms.expression.ConditionParser;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Terse builders for process definitions in tests. */
public final class Processes {

    private Processes() {
    }

    public static ProcessDefinition def(String key, List<Node> nodes, List<Edge> edges) {
        return new ProcessDefinition(key, 1, key, nodes, edges);
    }

    public static StartNode start(String id) {
        return new StartNode(id, id);
    }

    public static EndNode end(String id) {
        return new EndNode(id, id, null);
    }

    public static EndNode end(String id, String status) {
        return new EndNode(id, id, status);
    }

    public static UserTaskNode task(String id, Assignment assignment, String... outcomes) {
        return new UserTaskNode(id, id, assignment, Arrays.asList(outcomes), ApprovalMode.SINGLE, null, null);
    }

    public static UserTaskNode task(String id, Assignment assignment, ApprovalMode mode, Integer percent, Sla sla,
                                    String... outcomes) {
        return new UserTaskNode(id, id, assignment, Arrays.asList(outcomes), mode, percent, sla);
    }

    public static ExclusiveGatewayNode exclusive(String id) {
        return new ExclusiveGatewayNode(id, id);
    }

    public static ParallelGatewayNode parallel(String id) {
        return new ParallelGatewayNode(id, id);
    }

    public static TimerNode timer(String id, Duration duration) {
        return new TimerNode(id, id, duration, null);
    }

    public static TimerNode timerUntil(String id, String variable) {
        return new TimerNode(id, id, null, variable);
    }

    public static SystemActionNode action(String id, ActionType type, Map<String, Object> config) {
        return new SystemActionNode(id, id, type, config);
    }

    public static Edge flow(String from, String to) {
        return new Edge(from + "->" + to, from, to, null, false, null);
    }

    public static Edge outcome(String from, String outcome, String to) {
        return new Edge(from + ":" + outcome + "->" + to, from, to, null, false, outcome);
    }

    public static Edge when(String from, String to, Map<String, Object> condition) {
        return new Edge(from + "?->" + to, from, to, ConditionParser.parse(condition), false, null);
    }

    public static Edge otherwise(String from, String to) {
        return new Edge(from + "*->" + to, from, to, null, true, null);
    }

    public static Assignment user(String id) {
        return new Assignment.User(id);
    }

    public static Assignment group(String id) {
        return new Assignment.Group(id);
    }

    public static Assignment role(String name) {
        return new Assignment.Role(name);
    }

    public static Assignment manager(int levels) {
        return new Assignment.InitiatorManager(levels);
    }

    public static Assignment variableUser(String variable) {
        return new Assignment.VariableUser(variable);
    }

    public static List<String> codes(List<ValidationError> errors) {
        return errors.stream().map(ValidationError::code).toList();
    }

    /** start → review(alice: APPROVE/REJECT) → approved | rejected. */
    public static ProcessDefinition simpleApproval() {
        return def("simple", List.of(start("start"), task("review", user("alice"), "APPROVE", "REJECT"),
                        end("approved", "APPROVED"), end("rejected", "REJECTED")),
                List.of(flow("start", "review"), outcome("review", "APPROVE", "approved"),
                        outcome("review", "REJECT", "rejected")));
    }
}
