package lk.auradot.wms.expression;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntPredicate;

/** Evaluates parsed conditions; incompatible types give false and nothing ever throws (spec §3). */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    public static boolean evaluate(Condition condition, Map<String, ?> variables) {
        return switch (condition) {
            case Condition.All all -> all.conditions().stream().allMatch(c -> evaluate(c, variables));
            case Condition.Any any -> any.conditions().stream().anyMatch(c -> evaluate(c, variables));
            case Condition.Not not -> !evaluate(not.condition(), variables);
            case Condition.Compare compare -> compare(compare, Values.resolve(variables, compare.var()));
        };
    }

    private static boolean compare(Condition.Compare compare, Object actual) {
        Object expected = compare.value();
        return switch (compare.op()) {
            case EQ -> Values.equalValues(actual, expected);
            case NE -> notEqual(actual, expected);
            case GT -> numeric(actual, expected, r -> r > 0);
            case GTE -> numeric(actual, expected, r -> r >= 0);
            case LT -> numeric(actual, expected, r -> r < 0);
            case LTE -> numeric(actual, expected, r -> r <= 0);
            case IN -> expected instanceof List<?> list && actual != null
                    && list.stream().anyMatch(item -> Values.equalValues(actual, item));
            case NOT_IN -> expected instanceof List<?> list
                    && (actual == null || list.stream().noneMatch(item -> Values.equalValues(actual, item)));
            case CONTAINS -> contains(actual, expected);
            case IS_EMPTY -> Values.isEmpty(actual);
            case IS_NOT_EMPTY -> !Values.isEmpty(actual);
            case BEFORE -> temporal(actual, expected, r -> r < 0);
            case AFTER -> temporal(actual, expected, r -> r > 0);
        };
    }

    private static boolean notEqual(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return !(actual == null && expected == null);
        }
        return Values.sameKind(actual, expected) && !Values.equalValues(actual, expected);
    }

    private static boolean numeric(Object actual, Object expected, IntPredicate test) {
        return actual instanceof BigDecimal a && expected instanceof BigDecimal b && test.test(a.compareTo(b));
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual instanceof String text && expected instanceof String part) {
            return text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
        }
        if (actual instanceof List<?> list) {
            return list.stream().anyMatch(item -> Values.equalValues(item, expected));
        }
        return false;
    }

    private static boolean temporal(Object actual, Object expected, IntPredicate test) {
        Optional<Instant> a = Values.toInstant(actual);
        Optional<Instant> b = Values.toInstant(expected);
        return a.isPresent() && b.isPresent() && test.test(a.get().compareTo(b.get()));
    }
}
