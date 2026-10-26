package lk.auradot.wms.form.model;

import lk.auradot.wms.form.TestForms;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormJsonTest {

    @Test
    void readsTheExpenseClaimSample() {
        FormJson.Result result = FormJson.read(TestForms.load("expense-claim.json"));

        assertThat(result.errors()).isEmpty();
        FormDefinition form = result.definition();
        assertThat(form.sections()).extracting(Section::id).containsExactly("claim", "items");
        assertThat(form.sections().getFirst().columns()).isEqualTo(2);
        Field amount = form.fields().getFirst();
        assertThat(amount.type()).isEqualTo(FieldType.CURRENCY);
        assertThat(amount.required()).isTrue();
        assertThat(amount.min()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(form.fields().get(1).options()).extracting(Option::value).containsExactly("travel", "meals", "other");
        assertThat(form.fields().get(3).visibleWhen()).isNotNull();
        Field lines = form.fields().get(5);
        assertThat(lines.columns()).extracting(Field::key).containsExactly("description", "cost");
        assertThat(lines.totals()).containsExactly("cost");
        assertThat(lines.maxRows()).isEqualTo(20);
        assertThat(form.fields().get(4).type().holdsValue()).isFalse();
    }

    @Test
    void reportsMalformedPartsWithTheirField() {
        Map<String, Object> json = Map.of("fields", List.of(
                Map.of("key", "a", "type", "slider", "label", "A"),
                Map.of("key", "b", "type", "number", "label", "B", "min", "five"),
                Map.of("key", "c", "type", "text", "label", "C", "visibleWhen", Map.of("var", "form.a", "op", "equals", "value", 1)),
                Map.of("type", "text", "label", "No key")));

        FormJson.Result result = FormJson.read(json);

        assertThat(result.definition()).isNull();
        assertThat(result.errors()).extracting(FieldError::field).contains("a", "b", "c");
        assertThat(result.errors()).extracting(FieldError::code).contains("DEFINITION_INVALID", "CONDITION_INVALID");
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("slider"));
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("key"));
    }

    @Test
    void rejectsAFormThatIsNotAnObjectWithAFieldList() {
        assertThat(FormJson.read("fields").errors()).isNotEmpty();
        assertThat(FormJson.read(Map.of("fields", "x")).errors())
                .extracting(FieldError::message).anySatisfy(m -> assertThat(m).contains("fields"));
    }

    @Test
    void aBoundTooLargeToRepresentIsReportedNotThrown() {
        Map<String, Object> json = Map.of("fields", List.of(
                Map.of("key", "n", "type", "number", "label", "N", "min", Double.POSITIVE_INFINITY)));

        assertThat(FormJson.read(json).errors()).extracting(FieldError::field).containsExactly("n");
    }

    @Test
    void readsAFieldWidthAndRefusesOneThatIsNotAWholeNumber() {
        FormJson.Result wide = FormJson.read(Map.of("fields", List.of(
                Map.of("key", "a", "type", "text", "label", "A", "span", 2),
                Map.of("key", "b", "type", "text", "label", "B"))));

        assertThat(wide.errors()).isEmpty();
        assertThat(wide.definition().fields().get(0).span()).isEqualTo(2);
        assertThat(wide.definition().fields().get(1).span()).isNull();

        FormJson.Result broken = FormJson.read(Map.of("fields", List.of(
                Map.of("key", "half", "type", "text", "label", "Half", "span", 1.5),
                Map.of("key", "word", "type", "text", "label", "Word", "span", "wide"))));

        assertThat(broken.errors()).extracting(FieldError::field).containsExactly("half", "word");
    }
}
