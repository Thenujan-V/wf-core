package lk.auradot.wms.form.model;

import lk.auradot.wms.form.TestForms;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SubmissionValidatorTest {

    private final FormDefinition claim = FormJson.read(TestForms.load("expense-claim.json")).definition();

    @Test
    void messagesNameTheFieldInPlainWords() {
        var result = SubmissionValidator.validate(claim, Map.of("amount", 0, "category", "books"));

        assertThat(result.valid()).isFalse();
        assertThat(result.data()).isEmpty();
        assertThat(result.errors()).extracting(FieldError::message).contains(
                "Amount (LKR) must be at least 1", "Choose one of the offered options for Category",
                "Purpose is required");
    }

    @Test
    void acceptsAFullClaimWithReceipts() {
        Map<String, Object> data = Map.of("amount", 12500.5, "category", "travel", "purpose", "Client visit",
                "justification", "Two-day trip", "lines", List.of(Map.of("description", "Hotel", "cost", 12500.5)));

        var result = SubmissionValidator.validate(claim, data);

        assertThat(result.errors()).isEmpty();
        assertThat(result.data()).containsEntry("justification", "Two-day trip").containsKey("lines");
    }
}
