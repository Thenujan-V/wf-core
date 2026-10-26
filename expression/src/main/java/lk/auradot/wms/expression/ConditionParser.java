package lk.auradot.wms.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parses the JSON tree of a condition (as produced by any JSON library) and validates it strictly. */
public final class ConditionParser {

    static final int MAX_DEPTH = 32;
    private static final Set<String> COMPARE_KEYS = Set.of("var", "op", "value");

    private ConditionParser() {
    }

    public static Condition parse(Object node) {
        return parse(node, "$", 0);
    }

    private static Condition parse(Object node, String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw new ConditionParseException(path, "condition is nested too deeply");
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new ConditionParseException(path, "must be an object");
        }
        if (map.containsKey("all")) {
            requireOnly(map, "all", path);
            return new Condition.All(parseList(map.get("all"), path + ".all", depth));
        }
        if (map.containsKey("any")) {
            requireOnly(map, "any", path);
            return new Condition.Any(parseList(map.get("any"), path + ".any", depth));
        }
        if (map.containsKey("not")) {
            requireOnly(map, "not", path);
            return new Condition.Not(parse(map.get("not"), path + ".not", depth + 1));
        }
        if (map.containsKey("var")) {
            return parseCompare(map, path);
        }
        throw new ConditionParseException(path, "must contain one of all, any, not or var");
    }

    private static List<Condition> parseList(Object node, String path, int depth) {
        if (!(node instanceof List<?> list)) {
            throw new ConditionParseException(path, "must be a list");
        }
        List<Condition> conditions = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            conditions.add(parse(list.get(i), path + "[" + i + "]", depth + 1));
        }
        return conditions;
    }

    private static Condition parseCompare(Map<?, ?> map, String path) {
        for (Object key : map.keySet()) {
            if (!COMPARE_KEYS.contains(key)) {
                throw new ConditionParseException(path, "unexpected key '" + key + "'");
            }
        }
        if (!(map.get("var") instanceof String var) || var.isBlank()) {
            throw new ConditionParseException(path + ".var", "must be a non-empty string");
        }
        if (!(map.get("op") instanceof String opName)) {
            throw new ConditionParseException(path + ".op", "must be a string");
        }
        Operator op = Operator.fromJson(opName)
                .orElseThrow(() -> new ConditionParseException(path + ".op", "unknown operator '" + opName + "'"));
        boolean hasValue = map.containsKey("value");
        if (op.needsValue() && !hasValue) {
            throw new ConditionParseException(path + ".value", "is required for " + op.json());
        }
        if (!op.needsValue() && hasValue) {
            throw new ConditionParseException(path + ".value", "is not allowed for " + op.json());
        }
        Object value = literal(map.get("value"), path + ".value");
        String valuePath = path + ".value";
        switch (op) {
            case IN, NOT_IN -> {
                if (!(value instanceof List)) {
                    throw new ConditionParseException(valuePath, "must be a list for " + op.json());
                }
            }
            case GT, GTE, LT, LTE -> {
                if (!(value instanceof java.math.BigDecimal)) {
                    throw new ConditionParseException(valuePath, "must be a number for " + op.json());
                }
            }
            case BEFORE, AFTER -> {
                if (Values.toInstant(value).isEmpty()) {
                    throw new ConditionParseException(valuePath, "must be an ISO date or date-time for " + op.json());
                }
            }
            default -> {
            }
        }
        return new Condition.Compare(var, op, value);
    }

    private static Object literal(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number) {
            Object normalized = Values.normalize(value);
            if (normalized == null) {
                throw new ConditionParseException(path, "must be a finite number");
            }
            return normalized;
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                items.add(literal(list.get(i), path + "[" + i + "]"));
            }
            return java.util.Collections.unmodifiableList(items);
        }
        throw new ConditionParseException(path, "unsupported value type");
    }

    private static void requireOnly(Map<?, ?> map, String key, String path) {
        for (Object other : map.keySet()) {
            if (!key.equals(other)) {
                throw new ConditionParseException(path, "unexpected key '" + other + "'");
            }
        }
    }
}
