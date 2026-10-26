package lk.auradot.wms.form.model;

import lk.auradot.wms.form.TestForms;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionChecksTest {

    private static List<FieldError> check(Map<String, Object> json) {
        FormJson.Result result = FormJson.read(json);
        assertThat(result.errors()).as("the JSON itself is well formed").isEmpty();
        return DefinitionChecks.check(result.definition());
    }

    private static Map<String, Object> field(Object... pairs) {
        Map<String, Object> field = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            field.put((String) pairs[i], pairs[i + 1]);
        }
        return field;
    }

    private static Map<String, Object> form(Map<?, ?>... fields) {
        return Map.of("fields", List.of(fields));
    }

    private static List<String> codes(List<FieldError> errors) {
        return errors.stream().map(e -> e.field() + ":" + e.code()).toList();
    }

    @Test
    void theSampleFormPasses() {
        assertThat(check(TestForms.load("expense-claim.json"))).isEmpty();
    }

    @Test
    void keysLabelsAndSections() {
        List<FieldError> errors = check(Map.of(
                "sections", List.of(Map.of("id", "s", "title", "S", "columns", 5), Map.of("id", "s", "title", "Again")),
                "fields", List.of(
                        field("key", "1st", "type", "text", "label", "First"),
                        field("key", "name", "type", "text", "label", "Name"),
                        field("key", "name", "type", "text", "label", "Name again"),
                        field("key", "blank", "type", "text", "label", " "),
                        field("key", "where", "type", "text", "label", "Where", "section", "nowhere"))));

        assertThat(codes(errors)).contains("1st:KEY_INVALID", "name:KEY_DUPLICATE", "blank:LABEL_MISSING",
                "where:SECTION_UNKNOWN", "null:SECTION_INVALID", "null:SECTION_DUPLICATE");
    }

    @Test
    void aFormNeedsAnInputField() {
        assertThat(codes(check(form(field("key", "h", "type", "heading", "label", "Hello"))))).containsExactly("null:FIELDS_MISSING");
    }

    @Test
    void rulesMustFitTheFieldType() {
        List<FieldError> errors = check(form(
                field("key", "t", "type", "text", "label", "T", "min", 1),
                field("key", "n", "type", "number", "label", "N", "pattern", "x", "options", List.of(Map.of("value", "a"))),
                field("key", "r", "type", "number", "label", "R", "min", 5, "max", 1),
                field("key", "l", "type", "text", "label", "L", "minLength", 5, "maxLength", 2),
                field("key", "p", "type", "text", "label", "P", "pattern", "(unclosed"),
                field("key", "h", "type", "heading", "label", "H", "required", true)));

        assertThat(codes(errors)).contains("t:RULE_NOT_APPLICABLE", "n:RULE_NOT_APPLICABLE", "r:RANGE_INVALID",
                "l:RANGE_INVALID", "p:PATTERN_INVALID", "h:RULE_NOT_APPLICABLE");
    }

    @Test
    void choicesNeedDistinctOptions() {
        List<FieldError> errors = check(form(
                field("key", "s", "type", "select", "label", "S"),
                field("key", "r", "type", "radio", "label", "R",
                        "options", List.of(Map.of("value", "a"), Map.of("value", "a"), Map.of("value", ""))),
                field("key", "agree", "type", "checkbox", "label", "I agree")));

        assertThat(codes(errors)).containsExactlyInAnyOrder("s:OPTIONS_MISSING", "r:OPTION_DUPLICATE", "r:OPTION_INVALID");
    }

    @Test
    void tablesNeedSimpleColumnsAndNumericTotals() {
        List<FieldError> errors = check(form(
                field("key", "empty", "type", "table", "label", "Empty"),
                field("key", "lines", "type", "table", "label", "Lines", "totals", List.of("item", "missing"),
                        "minRows", 3, "maxRows", 1,
                        "columns", List.of(
                                field("key", "item", "type", "text", "label", "Item"),
                                field("key", "who", "type", "user", "label", "Who"),
                                field("key", "item", "type", "number", "label", "Again")))));

        assertThat(codes(errors)).contains("empty:TABLE_COLUMNS_MISSING", "lines.who:COLUMN_TYPE_INVALID",
                "lines.item:KEY_DUPLICATE", "lines:TOTAL_INVALID", "lines:RANGE_INVALID");
    }

    @Test
    void conditionsMayOnlyUseEarlierInputFields() {
        List<FieldError> errors = check(form(
                field("key", "a", "type", "text", "label", "A",
                        "visibleWhen", Map.of("var", "form.b", "op", "eq", "value", "x")),
                field("key", "b", "type", "text", "label", "B"),
                field("key", "c", "type", "text", "label", "C",
                        "requiredWhen", Map.of("var", "initiator", "op", "isNotEmpty")),
                field("key", "d", "type", "text", "label", "D",
                        "visibleWhen", Map.of("all", List.of(Map.of("var", "form.b", "op", "isNotEmpty"),
                                Map.of("var", "form.nope", "op", "isEmpty"))))));

        assertThat(codes(errors)).containsExactlyInAnyOrder("a:CONDITION_REFERENCE", "c:CONDITION_REFERENCE", "d:CONDITION_REFERENCE");
        assertThat(errors.getFirst().message()).contains("form.b");
    }

    @Test
    void patternsMustBeBoundedPortableAndSafe() {
        List<FieldError> errors = check(form(
                field("key", "noMax", "type", "text", "label", "A", "pattern", "[a-z]+"),
                field("key", "hugeMax", "type", "text", "label", "B", "pattern", "[a-z]+", "maxLength", 5000),
                field("key", "intersect", "type", "text", "label", "C", "pattern", "[a-z&&[^e]]", "maxLength", 10),
                field("key", "possessive", "type", "text", "label", "D", "pattern", "a*+b", "maxLength", 10),
                field("key", "atomic", "type", "text", "label", "E", "pattern", "(?>ab)", "maxLength", 10),
                field("key", "anchor", "type", "text", "label", "F", "pattern", "\\Aab", "maxLength", 10),
                field("key", "posix", "type", "text", "label", "G", "pattern", "\\p{Alpha}+", "maxLength", 10),
                field("key", "flags", "type", "text", "label", "H", "pattern", "(?i)ab", "maxLength", 10),
                field("key", "quoted", "type", "text", "label", "I", "pattern", "\\Qa.b\\E", "maxLength", 10),
                field("key", "nested", "type", "text", "label", "J", "pattern", "(.*a){12}", "maxLength", 100),
                field("key", "nested2", "type", "text", "label", "K", "pattern", "(\\w+\\s?)*", "maxLength", 100),
                field("key", "fine", "type", "text", "label", "L", "pattern", "(?:[A-Z]{3}-)?(?<num>\\d{2})(?=\\d)\\d", "maxLength", 20),
                field("key", "fine2", "type", "text", "label", "M", "pattern", "(\\d{3}-){2}\\d{4}|x+", "maxLength", 20)));

        assertThat(codes(errors)).containsExactlyInAnyOrder(
                "noMax:PATTERN_NEEDS_MAX_LENGTH", "hugeMax:PATTERN_NEEDS_MAX_LENGTH",
                "intersect:PATTERN_NOT_PORTABLE", "possessive:PATTERN_NOT_PORTABLE", "atomic:PATTERN_NOT_PORTABLE",
                "anchor:PATTERN_NOT_PORTABLE", "posix:PATTERN_NOT_PORTABLE", "flags:PATTERN_NOT_PORTABLE",
                "quoted:PATTERN_NOT_PORTABLE", "nested:PATTERN_UNSAFE", "nested2:PATTERN_UNSAFE");
    }

    @Test
    void repeatedAlternativesAreRefusedBecauseBrowsersCannotCheckThemSafely() {
        List<FieldError> errors = check(form(
                field("key", "overlap", "type", "text", "label", "A", "pattern", "(a|aa)*b", "maxLength", 50),
                field("key", "bounded", "type", "text", "label", "B", "pattern", "(ab|cd){2}", "maxLength", 50),
                field("key", "plain", "type", "text", "label", "C", "pattern", "ab|cd", "maxLength", 50)));

        assertThat(codes(errors)).containsExactly("overlap:PATTERN_UNSAFE");
    }

    @Test
    void nestedAndLargeBoundedAlternativesAreRefusedToo() {
        List<FieldError> errors = check(form(
                field("key", "nested", "type", "text", "label", "A", "pattern", "((a|aa))*b", "maxLength", 50),
                field("key", "nestedMore", "type", "text", "label", "B", "pattern", "((a|aa)c?)*b", "maxLength", 50),
                field("key", "largeBound", "type", "text", "label", "C", "pattern", "(a|aa){1,100}b", "maxLength", 50),
                field("key", "smallBound", "type", "text", "label", "D", "pattern", "(ab|cd){2,3}", "maxLength", 50)));

        assertThat(codes(errors)).containsExactlyInAnyOrder(
                "nested:PATTERN_UNSAFE", "nestedMore:PATTERN_UNSAFE", "largeBound:PATTERN_UNSAFE");
    }

    @Test
    void aSectionMayHaveFourColumnsAndAFieldMayTakeAllOfThem() {
        assertThat(check(Map.of(
                "sections", List.of(Map.of("id", "s", "title", "S", "columns", 4)),
                "fields", List.of(field("key", "a", "type", "text", "label", "A", "section", "s", "span", 4))))).isEmpty();
    }

    @Test
    void widthsAreOneToFourColumnsAndNotForTableColumns() {
        List<FieldError> errors = check(form(
                field("key", "zero", "type", "text", "label", "Zero", "span", 0),
                field("key", "five", "type", "text", "label", "Five", "span", 5),
                field("key", "loose", "type", "text", "label", "Loose", "span", 3),
                field("key", "lines", "type", "table", "label", "Lines",
                        "columns", List.of(field("key", "cost", "type", "number", "label", "Cost", "span", 2)))));

        assertThat(codes(errors)).containsExactlyInAnyOrder(
                "zero:SPAN_INVALID", "five:SPAN_INVALID", "lines.cost:RULE_NOT_APPLICABLE");
    }
}
