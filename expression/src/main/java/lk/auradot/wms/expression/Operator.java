package lk.auradot.wms.expression;

import java.util.Arrays;
import java.util.Optional;

/** Comparison operators of the rule builder, with their JSON names (spec §3). */
public enum Operator {
    EQ("eq"), NE("ne"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte"), IN("in"), NOT_IN("notIn"),
    CONTAINS("contains"), IS_EMPTY("isEmpty"), IS_NOT_EMPTY("isNotEmpty"), BEFORE("before"), AFTER("after");

    private final String json;

    Operator(String json) {
        this.json = json;
    }

    public String json() {
        return json;
    }

    public boolean needsValue() {
        return this != IS_EMPTY && this != IS_NOT_EMPTY;
    }

    public static Optional<Operator> fromJson(String json) {
        return Arrays.stream(values()).filter(op -> op.json.equals(json)).findFirst();
    }
}
