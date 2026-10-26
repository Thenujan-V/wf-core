package lk.auradot.wms.form.model;

import lk.auradot.wms.expression.ConditionEvaluator;
import lk.auradot.wms.expression.Values;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authoritative validation of submitted values (spec §5). The Angular renderer implements the same rules;
 * shared/forms/validation-vectors.json keeps the two in step.
 */
public final class SubmissionValidator {

    public record Result(Map<String, Object> data, List<FieldError> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final int USER_ID_MAX = 255;

    /** At most this many files in one file field. */
    public static final int MAX_FILES = 10;
    private static final int DOCUMENT_ID_MAX = 36;
    private static final int FILE_NAME_MAX = 255;

    private SubmissionValidator() {
    }

    public static Result validate(FormDefinition form, Map<String, ?> submitted) {
        return validate(form, submitted, Map.of(), Map.of());
    }

    public static Result validate(FormDefinition form, Map<String, ?> submitted, Map<String, Access> access,
                                  Map<String, ?> baseline) {
        List<FieldError> errors = new ArrayList<>();
        Map<String, Object> clean = new LinkedHashMap<>();
        Set<String> inputs = new HashSet<>();
        form.fields().stream().filter(f -> f.type().holdsValue()).forEach(f -> inputs.add(f.key()));
        for (String key : submitted.keySet()) {
            if (!inputs.contains(key)) {
                errors.add(new FieldError(key, "UNKNOWN_FIELD", "This form has no field '" + key + "'"));
            }
        }
        // Conditions see the values accepted so far; they may only reference earlier fields (DefinitionChecks).
        Map<String, Object> scope = Map.of("form", clean);
        for (Field field : form.fields()) {
            if (!field.type().holdsValue()) {
                continue;
            }
            if (field.visibleWhen() != null && !ConditionEvaluator.evaluate(field.visibleWhen(), scope)) {
                continue; // hidden fields are stripped and never validated
            }
            String key = field.key();
            if (access.getOrDefault(key, Access.EDITABLE) != Access.EDITABLE) {
                if (submitted.containsKey(key) && !same(submitted.get(key), baseline.get(key))) {
                    errors.add(new FieldError(key, "READ_ONLY", field.label() + " cannot be changed at this step"));
                }
                if (baseline.containsKey(key)) {
                    clean.put(key, baseline.get(key));
                }
                continue;
            }
            boolean required = field.required()
                    || (field.requiredWhen() != null && ConditionEvaluator.evaluate(field.requiredWhen(), scope));
            if (checkValue(field, key, submitted.get(key), required, errors)) {
                clean.put(key, submitted.get(key));
            }
        }
        return new Result(errors.isEmpty() ? clean : Map.of(), List.copyOf(errors));
    }

    /** Validates one value; true when it is present and valid (and should be kept). */
    private static boolean checkValue(Field field, String path, Object value, boolean required, List<FieldError> errors) {
        boolean flag = field.type() == FieldType.CHECKBOX && field.options().isEmpty();
        if (Values.isEmpty(value) || (flag && required && Boolean.FALSE.equals(value))) {
            if (required) {
                errors.add(new FieldError(path, "REQUIRED", field.label() + " is required"));
            }
            return false;
        }
        int before = errors.size();
        switch (field.type()) {
            case TEXT, TEXTAREA -> text(field, path, value, errors);
            case USER -> {
                if (!(value instanceof String id)) {
                    errors.add(invalidType(field, path));
                } else if (id.length() > USER_ID_MAX) {
                    errors.add(new FieldError(path, "TOO_LONG", field.label() + " is not a valid person"));
                }
            }
            case FILE -> files(field, path, value, errors);
            case NUMBER, CURRENCY -> number(field, path, value, errors);
            case DATE -> {
                if (!(value instanceof String text) || !DATE.matcher(text).matches() || !parses(() -> LocalDate.parse(text))) {
                    errors.add(new FieldError(path, "INVALID_DATE", field.label() + " must be a date (YYYY-MM-DD)"));
                }
            }
            case DATETIME -> {
                if (!(value instanceof String text) || !parses(() -> OffsetDateTime.parse(text))) {
                    errors.add(new FieldError(path, "INVALID_DATE", field.label() + " must be a date and time with a time zone"));
                }
            }
            case SELECT, RADIO -> {
                if (!(value instanceof String choice)) {
                    errors.add(invalidType(field, path));
                } else if (!offered(field, choice)) {
                    errors.add(notAnOption(field, path));
                }
            }
            case CHECKBOX -> checkbox(field, path, value, errors);
            case TABLE -> table(field, path, value, errors);
            case HEADING, DISPLAY -> errors.add(new FieldError(path, "UNKNOWN_FIELD", "This form has no field '" + path + "'"));
        }
        return errors.size() == before;
    }

    private static void text(Field field, String path, Object value, List<FieldError> errors) {
        if (!(value instanceof String text)) {
            errors.add(invalidType(field, path));
            return;
        }
        int before = errors.size();
        if (field.minLength() != null && text.length() < field.minLength()) {
            errors.add(new FieldError(path, "TOO_SHORT", field.label() + " must be at least " + field.minLength() + " characters"));
        }
        if (field.maxLength() != null && text.length() > field.maxLength()) {
            errors.add(new FieldError(path, "TOO_LONG", field.label() + " must be at most " + field.maxLength() + " characters"));
        }
        // A length error already rejects the value; skipping the pattern also bounds the work a pattern can do.
        if (field.pattern() != null && errors.size() == before && !PortablePatterns.matches(field.pattern(), text)) {
            errors.add(new FieldError(path, "PATTERN_MISMATCH", field.label() + " is not in the expected format"));
        }
    }

    private static void number(Field field, String path, Object value, List<FieldError> errors) {
        if (!(value instanceof Number number) || !finite(number)) {
            errors.add(invalidType(field, path)); // includes numbers too large to represent (e.g. 1e400)
            return;
        }
        BigDecimal decimal = new BigDecimal(number.toString());
        if (field.type() == FieldType.CURRENCY && decimal.stripTrailingZeros().scale() > 2) {
            errors.add(new FieldError(path, "TOO_MANY_DECIMALS", field.label() + " can have at most 2 decimal places"));
        }
        if (field.min() != null && decimal.compareTo(field.min()) < 0) {
            errors.add(new FieldError(path, "TOO_SMALL", field.label() + " must be at least " + field.min().toPlainString()));
        }
        if (field.max() != null && decimal.compareTo(field.max()) > 0) {
            errors.add(new FieldError(path, "TOO_LARGE", field.label() + " must be at most " + field.max().toPlainString()));
        }
    }

    private static void checkbox(Field field, String path, Object value, List<FieldError> errors) {
        if (field.options().isEmpty()) {
            if (!(value instanceof Boolean)) {
                errors.add(invalidType(field, path));
            }
            return;
        }
        if (!(value instanceof List<?> choices) || !choices.stream().allMatch(String.class::isInstance)) {
            errors.add(invalidType(field, path));
            return;
        }
        Set<Object> seen = new HashSet<>();
        if (!choices.stream().allMatch(choice -> offered(field, (String) choice) && seen.add(choice))) {
            errors.add(notAnOption(field, path));
        }
    }

    /** Spec §5 "file upload (document references)": [{documentId, name}], checked against document-service on submit. */
    private static void files(Field field, String path, Object value, List<FieldError> errors) {
        if (!(value instanceof List<?> items) || !items.stream().allMatch(SubmissionValidator::fileReference)) {
            errors.add(invalidType(field, path));
            return;
        }
        if (items.size() > MAX_FILES) {
            errors.add(new FieldError(path, "TOO_MANY_FILES", field.label() + " can have at most " + MAX_FILES + " files"));
        }
        Set<Object> seen = new HashSet<>();
        if (!items.stream().allMatch(item -> seen.add(((Map<?, ?>) item).get("documentId")))) {
            errors.add(new FieldError(path, "DUPLICATE_FILE", field.label() + " lists the same file twice"));
        }
    }

    private static boolean fileReference(Object item) {
        return item instanceof Map<?, ?> ref && ref.size() == 2
                && ref.get("documentId") instanceof String id && !id.isBlank() && id.length() <= DOCUMENT_ID_MAX
                && ref.get("name") instanceof String name && !name.isBlank() && name.length() <= FILE_NAME_MAX;
    }

    private static void table(Field field, String path, Object value, List<FieldError> errors) {
        if (!(value instanceof List<?> rows) || !rows.stream().allMatch(Map.class::isInstance)) {
            errors.add(invalidType(field, path));
            return;
        }
        if (field.minRows() != null && rows.size() < field.minRows()) {
            errors.add(new FieldError(path, "TOO_FEW_ROWS", field.label() + " needs at least " + field.minRows() + " rows"));
        }
        if (field.maxRows() != null && rows.size() > field.maxRows()) {
            errors.add(new FieldError(path, "TOO_MANY_ROWS", field.label() + " can have at most " + field.maxRows() + " rows"));
        }
        Set<String> columns = new HashSet<>();
        field.columns().forEach(column -> columns.add(column.key()));
        for (int i = 0; i < rows.size(); i++) {
            Map<?, ?> row = (Map<?, ?>) rows.get(i);
            String rowPath = path + "[" + i + "]";
            for (Object key : row.keySet()) {
                if (!columns.contains(String.valueOf(key))) {
                    errors.add(new FieldError(rowPath + "." + key, "UNKNOWN_FIELD", field.label() + " has no column '" + key + "'"));
                }
            }
            for (Field column : field.columns()) {
                checkValue(column, rowPath + "." + column.key(), row.get(column.key()), column.required(), errors);
            }
        }
    }

    private static boolean noValue(Object value) {
        return Values.isEmpty(value) || Boolean.FALSE.equals(value);
    }

    /** JSON numbers beyond double range arrive as infinity, which has no decimal value. */
    static boolean finite(Number number) {
        return !(number instanceof Double d && !Double.isFinite(d)) && !(number instanceof Float f && !Float.isFinite(f));
    }

    private static boolean offered(Field field, String choice) {
        return field.options().stream().anyMatch(option -> option.value().equals(choice));
    }

    private static FieldError invalidType(Field field, String path) {
        return new FieldError(path, "INVALID_TYPE", field.label() + " has a value of the wrong kind");
    }

    private static FieldError notAnOption(Field field, String path) {
        return new FieldError(path, "NOT_AN_OPTION", "Choose one of the offered options for " + field.label());
    }

    private static boolean parses(Runnable parse) {
        try {
            parse.run();
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * Value equality where 500 and 500.0 are the same number, and "no value" has several spellings: absent, blank
     * text, an empty list, or an unticked flag. Browsers send disabled fields as "" or false, which must not count
     * as changing a read-only field that never had a value.
     */
    private static boolean same(Object a, Object b) {
        if (noValue(a) && noValue(b)) {
            return true;
        }
        if (a instanceof Number x && b instanceof Number y) {
            if (!finite(x) || !finite(y)) {
                return x.equals(y);
            }
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!same(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            return x.keySet().equals(y.keySet()) && x.keySet().stream().allMatch(k -> same(x.get(k), y.get(k)));
        }
        return Objects.equals(a, b);
    }
}
