package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.common.error.ApiException;
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
class NodeFormsTest {

    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired NodeForms nodeForms;
    @Autowired StubForms forms;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        forms.reset();
        forms.publish("expense-claim", 1, "PUBLISHED", TestForms.load("expense-claim.json"));
        forms.publish("finance-review", 1, "PUBLISHED", TestForms.load("finance-review.json"));
    }

    @Test
    void publishedDefinitionsExposeTheirNodeForms() {
        definitions.saveDraft("expense-with-forms", "Expense", TestProcesses.load("expense-with-forms.json"), "bob");
        String id = definitions.publish("expense-with-forms", "bob").id();

        assertThat(nodeForms.of(id)).containsOnlyKeys("start", "financeApproval");
        assertThat(nodeForms.of(id).get("financeApproval").key()).isEqualTo("finance-review");
    }

    @Test
    void malformedFormSettingsBlockPublishing() {
        Map<String, Object> graph = TestProcesses.load("expense-with-forms.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        nodes.getFirst().put("form", Map.of("key", "expense-claim"));
        definitions.saveDraft("broken-form", "Broken", graph, "bob");

        assertThat(definitions.validateDraft("broken-form")).extracting("code").contains("FORM_REF_INVALID");
        assertThatThrownBy(() -> definitions.publish("broken-form", "bob"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WF_DEFINITION_INVALID"));
    }
}
