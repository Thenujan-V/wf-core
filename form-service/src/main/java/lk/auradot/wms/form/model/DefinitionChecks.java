package lk.auradot.wms.form.model;

import lk.auradot.wms.expression.Condition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Rules a form definition must satisfy before it can be published. */
public final class DefinitionChecks {

    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** The most columns a section may have, and so the widest a field may be. */
    public static final int MAX_COLUMNS = 4;

    private DefinitionChecks() {
    }

    public static List<FieldError> check(FormDefinition form) {
        List<FieldError> errors = new ArrayList<>();
        Set<String> sectionIds = new HashSet<>();
        for (Section section : form.sections()) {
            if (section.id() == null || section.id().isBlank()) {
                errors.add(new FieldError(null, "SECTION_INVALID", "Every section needs an id"));
            } else if (!sectionIds.add(section.id())) {
                errors.add(new FieldError(null, "SECTION_DUPLICATE", "Section '" + section.id() + "' is defined twice"));
            }
            if (section.columns() < 1 || section.columns() > MAX_COLUMNS) {
                errors.add(new FieldError(null, "SECTION_INVALID",
                        "Section '" + section.id() + "' must have 1 to " + MAX_COLUMNS + " columns"));
            }
        }
        if (form.fields().stream().noneMatch(f -> f.type().holdsValue())) {
            errors.add(new FieldError(null, "FIELDS_MISSING", "A form needs at least one input field"));
        }
        Set<String> keys = new HashSet<>();
        Set<String> earlierInputs = new HashSet<>();
        for (Field field : form.fields()) {
            String key = field.key();
            checkKey(key, key, keys, errors);
            if (field.label().isBlank()) {
                errors.add(new FieldError(key, "LABEL_MISSING", "Every field needs a label"));
            }
            if (field.section() != null && !sectionIds.contains(field.section())) {
                errors.add(new FieldError(key, "SECTION_UNKNOWN", "Section '" + field.section() + "' does not exist"));
            }
            if (field.span() != null && (field.span() < 1 || field.span() > MAX_COLUMNS)) {
                errors.add(new FieldError(key, "SPAN_INVALID", "Width must be 1 to " + MAX_COLUMNS + " columns"));
            }
            checkRules(field, key, errors);
            checkCondition(field.visibleWhen(), key, "show-when", earlierInputs, errors);
            checkCondition(field.requiredWhen(), key, "required-when", earlierInputs, errors);
            if (field.type() == FieldType.TABLE) {
                checkTable(field, errors);
            }
            if (field.type().holdsValue()) {
                earlierInputs.add(key);
            }
        }
        return errors;
    }

    private static void checkKey(String key, String path, Set<String> seen, List<FieldError> errors) {
        if (!KEY.matcher(key).matches()) {
            errors.add(new FieldError(path, "KEY_INVALID",
                    "Keys start with a letter and use only letters, digits or _ (at most 64 characters)"));
        }
        if (!seen.add(key)) {
            errors.add(new FieldError(path, "KEY_DUPLICATE", "The key '" + key + "' is used by more than one field"));
        }
    }

    private static void checkRules(Field field, String path, List<FieldError> errors) {
        FieldType type = field.type();
        boolean text = type == FieldType.TEXT || type == FieldType.TEXTAREA;
        notApplicable(field.min() != null || field.max() != null, !type.isNumeric(), "Minimum and maximum", path, errors);
        notApplicable(field.minLength() != null || field.maxLength() != null || field.pattern() != null, !text,
                "Length and pattern rules", path, errors);
        notApplicable(!field.options().isEmpty(), !type.hasOptions(), "Options", path, errors);
        notApplicable(!field.columns().isEmpty() || field.minRows() != null || field.maxRows() != null
                || !field.totals().isEmpty(), type != FieldType.TABLE, "Columns, rows and totals", path, errors);
        notApplicable(field.required() || field.requiredWhen() != null, !type.holdsValue(), "Required rules", path, errors);

        if (field.min() != null && field.max() != null && field.min().compareTo(field.max()) > 0) {
            errors.add(new FieldError(path, "RANGE_INVALID", "The minimum is larger than the maximum"));
        }
        if (negative(field.minLength()) || negative(field.maxLength()) || negative(field.minRows()) || negative(field.maxRows())
                || larger(field.minLength(), field.maxLength()) || larger(field.minRows(), field.maxRows())) {
            errors.add(new FieldError(path, "RANGE_INVALID", "Lengths and row counts must be 0 or more, minimum before maximum"));
        }
        if (field.pattern() != null) {
            checkPattern(field, path, errors);
        }
        if ((type == FieldType.SELECT || type == FieldType.RADIO) && field.options().isEmpty()) {
            errors.add(new FieldError(path, "OPTIONS_MISSING", "Add at least one option"));
        }
        Set<String> values = new HashSet<>();
        for (Option option : field.options()) {
            if (option.value().isBlank()) {
                errors.add(new FieldError(path, "OPTION_INVALID", "Every option needs a value"));
            } else if (!values.add(option.value())) {
                errors.add(new FieldError(path, "OPTION_DUPLICATE", "The option '" + option.value() + "' appears twice"));
            }
        }
    }

    private static void checkPattern(Field field, String path, List<FieldError> errors) {
        try {
            Pattern.compile(field.pattern());
        } catch (PatternSyntaxException e) {
            errors.add(new FieldError(path, "PATTERN_INVALID", "The pattern is not a valid regular expression"));
            return;
        }
        if (field.maxLength() == null || field.maxLength() > PortablePatterns.MAX_LENGTH) {
            errors.add(new FieldError(path, "PATTERN_NEEDS_MAX_LENGTH",
                    "A field with a pattern needs a maximum length of at most " + PortablePatterns.MAX_LENGTH));
        }
        PortablePatterns.notPortable(field.pattern()).ifPresent(reason ->
                errors.add(new FieldError(path, "PATTERN_NOT_PORTABLE", "The pattern uses " + reason)));
        if (PortablePatterns.unsafe(field.pattern())) {
            errors.add(new FieldError(path, "PATTERN_UNSAFE",
                    "The pattern repeats a group that itself repeats, which can take extremely long to check"));
        }
    }

    private static void checkTable(Field table, List<FieldError> errors) {
        if (table.columns().isEmpty()) {
            errors.add(new FieldError(table.key(), "TABLE_COLUMNS_MISSING", "A table needs at least one column"));
        }
        Set<String> keys = new HashSet<>();
        Set<String> numeric = new HashSet<>();
        for (Field column : table.columns()) {
            String path = table.key() + "." + column.key();
            checkKey(column.key(), path, keys, errors);
            if (column.label().isBlank()) {
                errors.add(new FieldError(path, "LABEL_MISSING", "Every column needs a label"));
            }
            if (!column.type().allowedInTable()) {
                errors.add(new FieldError(path, "COLUMN_TYPE_INVALID",
                        "Table columns can be text, number, currency, date or select"));
            }
            checkRules(column, path, errors);
            notApplicable(column.visibleWhen() != null || column.requiredWhen() != null, true,
                    "Show-when and required-when rules", path, errors);
            notApplicable(column.span() != null, true, "Widths", path, errors);
            if (column.type().isNumeric()) {
                numeric.add(column.key());
            }
        }
        for (String total : table.totals()) {
            if (!numeric.contains(total)) {
                errors.add(new FieldError(table.key(), "TOTAL_INVALID", "Only number or currency columns can be totalled: '" + total + "'"));
            }
        }
    }

    private static void checkCondition(Condition condition, String path, String rule, Set<String> earlierInputs,
                                       List<FieldError> errors) {
        if (condition == null) {
            return;
        }
        for (String variable : variables(condition)) {
            String key = variable.startsWith("form.") ? variable.substring(5) : null;
            if (key == null || !earlierInputs.contains(key)) {
                errors.add(new FieldError(path, "CONDITION_REFERENCE",
                        "The " + rule + " rule uses '" + variable + "'; it can only use fields above this one, as form.<key>"));
            }
        }
    }

    private static List<String> variables(Condition condition) {
        return switch (condition) {
            case Condition.All all -> all.conditions().stream().flatMap(c -> variables(c).stream()).toList();
            case Condition.Any any -> any.conditions().stream().flatMap(c -> variables(c).stream()).toList();
            case Condition.Not not -> variables(not.condition());
            case Condition.Compare compare -> List.of(compare.var());
        };
    }

    private static void notApplicable(boolean used, boolean wrongType, String rule, String path, List<FieldError> errors) {
        if (used && wrongType) {
            errors.add(new FieldError(path, "RULE_NOT_APPLICABLE", rule + " do not apply to this field type"));
        }
    }

    private static boolean negative(Integer value) {
        return value != null && value < 0;
    }

    private static boolean larger(Integer min, Integer max) {
        return min != null && max != null && min > max;
    }
}
