package lk.auradot.wms.form.model;

import lk.auradot.wms.expression.Condition;

import java.math.BigDecimal;
import java.util.List;

/** One form field; tables carry their columns as nested fields. Unused rules are null or empty. */
public record Field(String key, FieldType type, String label, String section, Integer span, String help, boolean required,
                    BigDecimal min, BigDecimal max, Integer minLength, Integer maxLength, String pattern,
                    List<Option> options, List<Field> columns, Integer minRows, Integer maxRows, List<String> totals,
                    Condition visibleWhen, Condition requiredWhen) {

    public Field {
        options = List.copyOf(options);
        columns = List.copyOf(columns);
        totals = List.copyOf(totals);
    }
}
