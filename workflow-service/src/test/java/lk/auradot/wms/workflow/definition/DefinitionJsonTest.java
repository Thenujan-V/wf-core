package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.definition.Assignment;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.definition.ProcessCompiler;
import lk.auradot.wms.engine.definition.SystemActionNode;
import lk.auradot.wms.engine.definition.UserTaskNode;
import lk.auradot.wms.engine.definition.ValidationError;
import lk.auradot.wms.workflow.TestProcesses;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionJsonTest {

    @Test
    void readsTheExpenseSampleIntoACompilableDefinition() {
        DefinitionJson.Result result = DefinitionJson.read("expense-approval", 3, "Expense approval",
                TestProcesses.load("expense-approval.json"));

        assertThat(result.errors()).isEmpty();
        assertThat(result.definition().version()).isEqualTo(3);
        var process = ProcessCompiler.compile(result.definition());
        UserTaskNode manager = (UserTaskNode) process.node("managerApproval");
        assertThat(manager.assignment()).isEqualTo(new Assignment.InitiatorManager(1));
        assertThat(manager.outcomes()).containsExactly("APPROVE", "REJECT");
        assertThat(manager.sla().dueIn()).isEqualTo(Duration.ofHours(48));
        assertThat(manager.sla().onBreach()).isEqualTo(BreachAction.NOTIFY);
        SystemActionNode notify = (SystemActionNode) process.node("notifyRequester");
        assertThat(notify.type()).isEqualTo(ActionType.SEND_EMAIL);
        assertThat(notify.config()).containsEntry("template", "expense-approved");
        assertThat(process.outgoing("amountCheck")).anySatisfy(e -> assertThat(e.defaultFlow()).isTrue());
    }

    @Test
    void reportsMalformedPartsWithTheirNode() {
        Map<String, Object> graph = Map.of(
                "nodes", List.of(
                        Map.of("id", "s", "type", "START"),
                        Map.of("id", "t", "type", "USER_TASK", "assignment", Map.of("type", "BOSS"),
                                "outcomes", List.of("OK"), "sla", Map.of("dueIn", "48 hours", "onBreach", "NOTIFY")),
                        Map.of("id", "x", "type", "LOOP")),
                "edges", List.of(
                        Map.of("id", "e1", "source", "s", "target", "t",
                                "condition", Map.of("var", "a", "op", "equals", "value", 1))));

        List<ValidationError> errors = DefinitionJson.read("p", 1, "p", graph).errors();

        assertThat(errors).extracting(ValidationError::nodeId).contains("t", "x", "s");
        assertThat(errors).extracting(ValidationError::code).contains("JSON_INVALID", "CONDITION_INVALID");
        assertThat(errors).anySatisfy(e -> assertThat(e.message()).contains("ISO-8601"));
        assertThat(DefinitionJson.read("p", 1, "p", graph).definition()).isNull();
    }

    @Test
    void rejectsAGraphThatIsNotAnObjectWithLists() {
        assertThat(DefinitionJson.read("p", 1, "p", "nodes").errors()).isNotEmpty();
        assertThat(DefinitionJson.read("p", 1, "p", Map.of("nodes", "x", "edges", List.of())).errors())
                .extracting(ValidationError::message).anySatisfy(m -> assertThat(m).contains("nodes"));
    }
}
