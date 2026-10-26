package lk.auradot.wms.expression;

import java.util.List;
import java.util.Objects;

/** A condition built in the visual rule builder and stored as JSON (spec §3). */
public sealed interface Condition permits Condition.All, Condition.Any, Condition.Not, Condition.Compare {

    /** True when every child is true; an empty list is true. */
    record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = List.copyOf(conditions);
        }
    }

    /** True when at least one child is true; an empty list is false. */
    record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = List.copyOf(conditions);
        }
    }

    record Not(Condition condition) implements Condition {
        public Not {
            Objects.requireNonNull(condition, "condition");
        }
    }

    /** {@code value} is a normalised literal: null, String, Boolean, BigDecimal, or a List of those. */
    record Compare(String var, Operator op, Object value) implements Condition {
        public Compare {
            Objects.requireNonNull(var, "var");
            Objects.requireNonNull(op, "op");
        }
    }
}
