package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.engine.definition.ValidationError;
import lk.auradot.wms.workflow.TestProcesses;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessFormsTest {

    @Test
    void readsStartAndTaskForms() {
        ProcessForms.Result result = ProcessForms.read(TestProcesses.load("expense-with-forms.json"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.forms()).containsOnlyKeys("start", "financeApproval");
        assertThat(result.forms().get("start")).isEqualTo(new FormRef("expense-claim", 1, Map.of()));
        assertThat(result.forms().get("financeApproval").access())
                .containsEntry("amount", "READONLY").containsEntry("category", "HIDDEN");
    }

    @Test
    void reportsFormsInTheWrongPlaceOrOfTheWrongShape() {
        Map<String, Object> graph = Map.of("nodes", List.of(
                Map.of("id", "s", "type", "START", "form", Map.of("key", "form-f", "version", 1, "access", Map.of("a", "READONLY"))),
                Map.of("id", "g", "type", "EXCLUSIVE_GATEWAY", "form", Map.of("key", "form-f", "version", 1)),
                Map.of("id", "t1", "type", "USER_TASK", "form", Map.of("key", "form-f")),
                Map.of("id", "t2", "type", "USER_TASK", "form", Map.of("key", "form-f", "version", 1, "access", Map.of("a", "WRITE"))),
                Map.of("id", "t3", "type", "USER_TASK", "form", "form-f@1")));

        ProcessForms.Result result = ProcessForms.read(graph);

        assertThat(result.forms()).isEmpty();
        assertThat(result.errors()).extracting(e -> e.nodeId() + ":" + e.code()).containsExactlyInAnyOrder(
                "s:FORM_REF_INVALID", "g:FORM_NOT_ALLOWED", "t1:FORM_REF_INVALID", "t2:FORM_REF_INVALID", "t3:FORM_REF_INVALID");
        assertThat(result.errors()).extracting(ValidationError::message)
                .anySatisfy(m -> assertThat(m).contains("tasks only"));
    }

    @Test
    void formKeysMustBeValidFormServiceKeys() {
        Map<String, Object> graph = Map.of("nodes", List.of(
                Map.of("id", "t", "type", "USER_TASK", "form", Map.of("key", "Expense Claim", "version", 1))));

        assertThat(ProcessForms.read(graph).errors()).extracting(e -> e.nodeId() + ":" + e.code())
                .containsExactly("t:FORM_REF_INVALID");
    }
}
