package lk.auradot.wms.form.model;

import lk.auradot.wms.expression.Condition;
import lk.auradot.wms.expression.ConditionParseException;
import lk.auradot.wms.expression.ConditionParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Reads the form builder's JSON (already parsed into maps and lists) into the typed model. */
public final class FormJson {

    public record Result(FormDefinition definition, List<FieldError> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private final List<FieldError> errors = new ArrayList<>();

    private FormJson() {
    }

    public static Result read(Object json) {
        FormJson reader = new FormJson();
        FormDefinition definition = reader.definition(json);
        return new Result(reader.errors.isEmpty() ? definition : null, List.copyOf(reader.errors));
    }

    private FormDefinition definition(Object json) {
        if (!(json instanceof Map<?, ?> map)) {
            error(null, "A form must be an object with a 'fields' list");
            return null;
        }
        List<Section> sections = new ArrayList<>();
        if (map.get("sections") != null) {
            for (Map<?, ?> section : objects(map.get("sections"), "sections", null)) {
                sections.add(section(section));
            }
        }
        List<Field> fields = new ArrayList<>();
        for (Map<?, ?> field : objects(map.get("fields"), "fields", null)) {
            Field parsed = field(field, null);
            if (parsed != null) {
                fields.add(parsed);
            }
        }
        return new FormDefinition(sections, fields);
    }

    private Section section(Map<?, ?> section) {
        String id = text(section, "id", null);
        Integer columns = integer(section.get("columns"), id, "columns");
        return new Section(id, Objects.requireNonNullElse(text(section, "title", id), ""), columns == null ? 1 : columns);
    }

    private Field field(Map<?, ?> field, String table) {
        String key = text(field, "key", table);
        if (key == null || key.isBlank()) {
            error(table, "Every field needs a key");
            return null;
        }
        String path = table == null ? key : table + "." + key;
        String typeName = text(field, "type", path);
        if (typeName == null) {
            error(path, "The field type is missing");
            return null;
        }
        Optional<FieldType> type = FieldType.fromJson(typeName);
        if (type.isEmpty()) {
            error(path, "Unknown field type '" + typeName + "'");
            return null;
        }
        List<Field> columns = new ArrayList<>();
        if (field.get("columns") != null) {
            for (Map<?, ?> column : objects(field.get("columns"), "columns", path)) {
                Field parsed = field(column, key);
                if (parsed != null) {
                    columns.add(parsed);
                }
            }
        }
        return new Field(key, type.get(), Objects.requireNonNullElse(text(field, "label", path), ""),
                text(field, "section", path), integer(field.get("span"), path, "span"), text(field, "help", path),
                bool(field.get("required"), path, "required"),
                decimal(field.get("min"), path, "min"), decimal(field.get("max"), path, "max"),
                integer(field.get("minLength"), path, "minLength"), integer(field.get("maxLength"), path, "maxLength"),
                text(field, "pattern", path), options(field.get("options"), path), columns,
                integer(field.get("minRows"), path, "minRows"), integer(field.get("maxRows"), path, "maxRows"),
                texts(field.get("totals"), path, "totals"),
                condition(field.get("visibleWhen"), path, "visibleWhen"),
                condition(field.get("requiredWhen"), path, "requiredWhen"));
    }

    private List<Option> options(Object value, String path) {
        List<Option> options = new ArrayList<>();
        if (value == null) {
            return options;
        }
        for (Map<?, ?> option : objects(value, "options", path)) {
            String optionValue = text(option, "value", path);
            options.add(new Option(Objects.requireNonNullElse(optionValue, ""),
                    Objects.requireNonNullElse(text(option, "label", path), Objects.requireNonNullElse(optionValue, ""))));
        }
        return options;
    }

    private Condition condition(Object value, String path, String name) {
        if (value == null) {
            return null;
        }
        try {
            return ConditionParser.parse(value);
        } catch (ConditionParseException e) {
            errors.add(new FieldError(path, "CONDITION_INVALID", "The " + name + " rule is invalid: " + e.getMessage()));
            return null;
        }
    }

    // ---------------------------------------------------------------- typed readers

    private List<Map<?, ?>> objects(Object value, String name, String path) {
        if (!(value instanceof List<?> list)) {
            error(path, "'" + name + "' must be a list");
            return List.of();
        }
        List<Map<?, ?>> items = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                items.add(map);
            } else {
                error(path, "Every entry of '" + name + "' must be an object");
            }
        }
        return items;
    }

    private String text(Map<?, ?> map, String name, String path) {
        Object value = map.get(name);
        if (value == null || value instanceof String) {
            return (String) value;
        }
        error(path, "'" + name + "' must be text");
        return null;
    }

    private List<String> texts(Object value, String path, String name) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        error(path, "'" + name + "' must be a list of keys");
        return List.of();
    }

    private Integer integer(Object value, String path, String name) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        error(path, "'" + name + "' must be a whole number");
        return null;
    }

    private BigDecimal decimal(Object value, String path, String name) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && SubmissionValidator.finite(number)) {
            return new BigDecimal(number.toString());
        }
        error(path, "'" + name + "' must be a number");
        return null;
    }

    private boolean bool(Object value, String path, String name) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        error(path, "'" + name + "' must be true or false");
        return false;
    }

    private void error(String path, String message) {
        errors.add(new FieldError(path, "DEFINITION_INVALID", message));
    }
}
