package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.definition.ValidationError;
import lk.auradot.wms.workflow.StubForms;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestForms;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WorkflowIntegrationTest
class FormReferenceChecksTest {

    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired StubForms forms;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        forms.reset();
        forms.publish("expense-claim", 1, "PUBLISHED", TestForms.load("expense-claim.json"));
        forms.publish("finance-review", 1, "PUBLISHED", TestForms.load("finance-review.json"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> financeForm(Map<String, Object> graph) {
        return (Map<String, Object>) ((List<Map<String, Object>>) graph.get("nodes")).stream()
                .filter(n -> "financeApproval".equals(n.get("id"))).findFirst().orElseThrow().get("form");
    }

    private List<String> problems(Map<String, Object> graph) {
        definitions.saveDraft("checked", "Checked", graph, "bob");
        return definitions.validateDraft("checked").stream().map(e -> e.nodeId() + ":" + e.code()).toList();
    }

    @Test
    void aProcessWithPublishedFormsCanBePublished() {
        definitions.saveDraft("expense-with-forms", "Expense", TestProcesses.load("expense-with-forms.json"), "bob");

        assertThat(definitions.validateDraft("expense-with-forms")).isEmpty();
        assertThat(definitions.publish("expense-with-forms", "bob").version()).isEqualTo(1);
    }

    @Test
    void missingAndRetiredFormsAreReported() {
        forms.publish("finance-review", 1, "RETIRED", TestForms.load("finance-review.json"));
        Map<String, Object> graph = TestProcesses.load("expense-with-forms.json");
        @SuppressWarnings("unchecked")
        Map<String, Object> start = ((List<Map<String, Object>>) graph.get("nodes")).getFirst();
        start.put("form", Map.of("key", "expense-claim", "version", 7));

        assertThat(problems(graph)).containsExactlyInAnyOrder("start:FORM_NOT_FOUND", "financeApproval:FORM_RETIRED");
    }

    @Test
    void accessMustNameRealFieldsAndNotHideWhatConditionsNeed() {
        Map<String, Object> unknown = TestProcesses.load("expense-with-forms.json");
        financeForm(unknown).put("access", Map.of("amountt", "READONLY"));
        assertThat(problems(unknown)).containsExactly("financeApproval:ACCESS_UNKNOWN_FIELD");

        Map<String, Object> hidden = TestProcesses.load("expense-with-forms.json");
        financeForm(hidden).putAll(Map.of("key", "expense-claim", "access", Map.of("amount", "HIDDEN")));
        List<ValidationError> errors = definitions.validateDraft(saveAndKey(hidden));
        assertThat(errors).extracting(ValidationError::code).containsExactly("ACCESS_HIDDEN_IN_CONDITION");
        assertThat(errors.getFirst().message()).contains("justification").contains("amount");
    }

    private String saveAndKey(Map<String, Object> graph) {
        definitions.saveDraft("hidden-check", "Hidden", graph, "bob");
        return "hidden-check";
    }

    @Test
    void publishingWaitsForFormServiceInsteadOfSkippingTheChecks() {
        definitions.saveDraft("expense-with-forms", "Expense", TestProcesses.load("expense-with-forms.json"), "bob");
        forms.failing(true);

        assertThatThrownBy(() -> definitions.publish("expense-with-forms", "bob"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("WF_FORM_SERVICE_UNAVAILABLE");
                    assertThat(e.status().value()).isEqualTo(503);
                });
        assertThat(definitions.latestPublished("expense-with-forms")).isEmpty();
    }
}
