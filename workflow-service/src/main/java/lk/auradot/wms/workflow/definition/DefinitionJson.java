package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.definition.ApprovalMode;
import lk.auradot.wms.engine.definition.Assignment;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.definition.Edge;
import lk.auradot.wms.engine.definition.EndNode;
import lk.auradot.wms.engine.definition.ExclusiveGatewayNode;
import lk.auradot.wms.engine.definition.Node;
import lk.auradot.wms.engine.definition.ParallelGatewayNode;
import lk.auradot.wms.engine.definition.ProcessDefinition;
import lk.auradot.wms.engine.definition.Sla;
import lk.auradot.wms.engine.definition.StartNode;
import lk.auradot.wms.engine.definition.SystemActionNode;
import lk.auradot.wms.engine.definition.TimerNode;
import lk.auradot.wms.engine.definition.UserTaskNode;
import lk.auradot.wms.engine.definition.ValidationError;
import lk.auradot.wms.expression.Condition;
import lk.auradot.wms.expression.ConditionParseException;
import lk.auradot.wms.expression.ConditionParser;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reads the designer's graph JSON (already parsed into maps and lists) into the engine's definition model.
 * Structural mistakes become {@link ValidationError}s so the designer can show them next to the node.
 */
public final class DefinitionJson {

    public record Result(ProcessDefinition definition, List<ValidationError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final List<ValidationError> errors = new ArrayList<>();

    private DefinitionJson() {
    }

    public static Result read(String key, int version, String name, Object graph) {
        DefinitionJson reader = new DefinitionJson();
        ProcessDefinition definition = reader.definition(key, version, name, graph);
        return new Result(reader.errors.isEmpty() ? definition : null, List.copyOf(reader.errors));
    }

    private ProcessDefinition definition(String key, int version, String name, Object graph) {
        if (!(graph instanceof Map<?, ?> map)) {
            error(null, "The process graph must be an object with 'nodes' and 'edges'");
            return null;
        }
        List<Node> nodes = new ArrayList<>();
        for (Map<?, ?> item : objects(map.get("nodes"), "nodes")) {
            Node node = node(item);
            if (node != null) {
                nodes.add(node);
            }
        }
        List<Edge> edges = new ArrayList<>();
        for (Map<?, ?> item : objects(map.get("edges"), "edges")) {
            Edge edge = edge(item);
            if (edge != null) {
                edges.add(edge);
            }
        }
        return new ProcessDefinition(key, version, name, nodes, edges);
    }

    private List<Map<?, ?>> objects(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            error(null, "'" + field + "' must be a list");
            return List.of();
        }
        List<Map<?, ?>> items = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                items.add(map);
            } else {
                error(null, "Every entry of '" + field + "' must be an object");
            }
        }
        return items;
    }

    private Node node(Map<?, ?> node) {
        String id = text(node, "id", null);
        if (id == null || id.isBlank()) {
            error(null, "Every node needs an id");
            return null;
        }
        String name = Objects.requireNonNullElse(text(node, "name", id), id);
        String type = text(node, "type", id);
        if (type == null) {
            error(id, "The node type is missing");
            return null;
        }
        return switch (type) {
            case "START" -> new StartNode(id, name);
            case "END" -> new EndNode(id, name, text(node, "endStatus", id));
            case "USER_TASK" -> userTask(node, id, name);
            case "EXCLUSIVE_GATEWAY" -> new ExclusiveGatewayNode(id, name);
            case "PARALLEL_GATEWAY" -> new ParallelGatewayNode(id, name);
            case "TIMER" -> new TimerNode(id, name, duration(node.get("duration"), id, "duration"),
                    text(node, "dueDateVariable", id));
            case "SYSTEM_ACTION" -> new SystemActionNode(id, name,
                    enumValue(ActionType.class, node.get("actionType"), id, "actionType"),
                    object(node.get("config"), id, "config"));
            default -> {
                error(id, "Unknown node type '" + type + "'");
                yield null;
            }
        };
    }

    private UserTaskNode userTask(Map<?, ?> node, String id, String name) {
        ApprovalMode mode = Objects.requireNonNullElse(
                enumValue(ApprovalMode.class, node.get("approvalMode"), id, "approvalMode"), ApprovalMode.SINGLE);
        return new UserTaskNode(id, name, assignment(node.get("assignment"), id), texts(node.get("outcomes"), id),
                mode, integer(node.get("percent"), id, "percent"), sla(node.get("sla"), id));
    }

    private Assignment assignment(Object value, String id) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> a)) {
            error(id, "'assignment' must be an object");
            return null;
        }
        String type = text(a, "type", id);
        return switch (type == null ? "" : type) {
            case "USER" -> new Assignment.User(text(a, "userId", id));
            case "GROUP" -> new Assignment.Group(text(a, "groupId", id));
            case "ROLE" -> new Assignment.Role(text(a, "role", id));
            case "INITIATOR_MANAGER" -> new Assignment.InitiatorManager(
                    Objects.requireNonNullElse(integer(a.get("levels"), id, "levels"), 1));
            case "VARIABLE_USER" -> new Assignment.VariableUser(text(a, "variable", id));
            default -> {
                error(id, "Unknown assignment type '" + type + "'");
                yield null;
            }
        };
    }

    private Sla sla(Object value, String id) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> s)) {
            error(id, "'sla' must be an object");
            return null;
        }
        return new Sla(duration(s.get("dueIn"), id, "sla.dueIn"), bool(s.get("businessDays"), id, "sla.businessDays"),
                integer(s.get("reminderPercent"), id, "sla.reminderPercent"),
                enumValue(BreachAction.class, s.get("onBreach"), id, "sla.onBreach"), text(s, "autoOutcome", id));
    }

    private Edge edge(Map<?, ?> edge) {
        String id = text(edge, "id", null);
        String source = text(edge, "source", null);
        String target = text(edge, "target", null);
        if (id == null || source == null || target == null) {
            error(source, "Every flow needs an id, a source and a target");
            return null;
        }
        Condition condition = null;
        if (edge.get("condition") != null) {
            try {
                condition = ConditionParser.parse(edge.get("condition"));
            } catch (ConditionParseException e) {
                errors.add(new ValidationError(source, "CONDITION_INVALID",
                        "The condition of flow '" + id + "' is invalid: " + e.getMessage()));
                return null;
            }
        }
        return new Edge(id, source, target, condition, bool(edge.get("default"), source, "default"),
                text(edge, "outcome", source));
    }

    // ---------------------------------------------------------------- typed readers

    private String text(Map<?, ?> map, String field, String nodeId) {
        Object value = map.get(field);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        error(nodeId, "'" + field + "' must be text");
        return null;
    }

    private List<String> texts(Object value, String nodeId) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        error(nodeId, "'outcomes' must be a list of names");
        return List.of();
    }

    private Integer integer(Object value, String nodeId, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        error(nodeId, "'" + field + "' must be a whole number");
        return null;
    }

    private boolean bool(Object value, String nodeId, String field) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        error(nodeId, "'" + field + "' must be true or false");
        return false;
    }

    private Duration duration(Object value, String nodeId, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            try {
                return Duration.parse(text);
            } catch (DateTimeParseException e) {
                error(nodeId, "'" + field + "' must be an ISO-8601 duration such as PT48H or P2D");
                return null;
            }
        }
        error(nodeId, "'" + field + "' must be an ISO-8601 duration such as PT48H or P2D");
        return null;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, Object value, String nodeId, String field) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            try {
                return Enum.valueOf(type, text);
            } catch (IllegalArgumentException e) {
                error(nodeId, "Unknown " + field + " '" + text + "'");
                return null;
            }
        }
        error(nodeId, "'" + field + "' must be text");
        return null;
    }

    private Map<String, Object> object(Object value, String nodeId, String field) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        error(nodeId, "'" + field + "' must be an object");
        return Map.of();
    }

    private void error(String nodeId, String message) {
        errors.add(new ValidationError(nodeId, "JSON_INVALID", message));
    }
}
