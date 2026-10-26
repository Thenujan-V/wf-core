package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.CompiledProcess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class ExclusiveGatewayTest {

    private final Fixture fx = new Fixture();

    /**
     * start → amountCheck: form.amount > 10000 → cfoApproval, otherwise → managerApproval.
     * managerApproval can SEND_BACK to rework (initiator), which loops back to amountCheck.
     */
    private final CompiledProcess process = fx.compile(def("expense",
            List.of(start("s"), exclusive("amountCheck"), task("cfoApproval", user("cfo"), "APPROVE", "REJECT"),
                    task("managerApproval", user("bob"), "APPROVE", "REJECT", "SEND_BACK"),
                    task("rework", variableUser("initiator"), "RESUBMIT"), end("approved", "APPROVED"),
                    end("rejected", "REJECTED")),
            List.of(flow("s", "amountCheck"),
                    when("amountCheck", "cfoApproval", Map.of("var", "form.amount", "op", "gt", "value", 10000)),
                    otherwise("amountCheck", "managerApproval"),
                    outcome("cfoApproval", "APPROVE", "approved"), outcome("cfoApproval", "REJECT", "rejected"),
                    outcome("managerApproval", "APPROVE", "approved"), outcome("managerApproval", "REJECT", "rejected"),
                    outcome("managerApproval", "SEND_BACK", "rework"), outcome("rework", "RESUBMIT", "amountCheck"))));

    private String openNode(InstanceState state) {
        return state.openTasks().getFirst().nodeId();
    }

    @Test
    void routesByConditionAndDefault() {
        assertThat(openNode(fx.engine.start(process, "carol", Map.of("amount", 12500), null))).isEqualTo("cfoApproval");
        InstanceState small = fx.engine.start(process, "carol", Map.of("amount", 500), null);
        assertThat(openNode(small)).isEqualTo("managerApproval");
        assertThat(small.drainEvents()).contains(
                new EngineEvent.GatewayRouted("amountCheck", "amountCheck*->managerApproval"));
    }

    @Test
    void wrongTypesAndMissingValuesTakeTheDefaultInsteadOfFailing() {
        assertThat(openNode(fx.engine.start(process, "carol", Map.of("amount", "12500"), null)))
                .as("text is not a number").isEqualTo("managerApproval");
        assertThat(openNode(fx.engine.start(process, "carol", Map.of(), null))).isEqualTo("managerApproval");
    }

    @Test
    void sendBackLoopsThroughReworkAndReevaluates() {
        InstanceState s = fx.engine.start(process, "carol", Map.of("amount", 500), null);
        fx.engine.completeTask(process, s, s.openTasks().getFirst().id(), "bob", "SEND_BACK", Map.of());

        Task rework = s.openTasks().getFirst();
        assertThat(rework.nodeId()).isEqualTo("rework");
        assertThat(rework.assigneeId()).isEqualTo("carol");
        assertThat(s.variables()).containsEntry("nodes", Map.of("managerApproval", Map.of("outcome", "SEND_BACK")));

        fx.engine.completeTask(process, s, rework.id(), "carol", "RESUBMIT", Map.of("amount", 20000));

        assertThat(openNode(s)).isEqualTo("cfoApproval");
        assertThat(s.tokens()).hasSize(1);
    }

    @Test
    void conditionsSeeRequesterProfileAndNow() {
        fx.directory.department("carol", "Finance");
        CompiledProcess p = fx.compile(def("ctx",
                List.of(start("s"), exclusive("x"), task("finance", user("f"), "OK"), task("late", user("l"), "OK"),
                        task("other", user("o"), "OK"), end("e")),
                List.of(flow("s", "x"),
                        when("x", "late", Map.of("all", List.of(
                                Map.of("var", "form.deadline", "op", "isNotEmpty"),
                                Map.of("var", "now", "op", "after", "value", "2026-09-01")))),
                        when("x", "finance", Map.of("var", "requester.department", "op", "eq", "value", "Finance")),
                        otherwise("x", "other"),
                        outcome("finance", "OK", "e"), outcome("late", "OK", "e"), outcome("other", "OK", "e"))));

        assertThat(openNode(fx.engine.start(p, "carol", Map.of(), null))).isEqualTo("finance");
        assertThat(openNode(fx.engine.start(p, "carol", Map.of("deadline", "2026-01-01"), null)))
                .as("'now' (2026-09-28) is after 2026-09-01; first true condition in flow order wins").isEqualTo("late");
        assertThat(openNode(fx.engine.start(p, "dave", Map.of(), null))).isEqualTo("other");
    }
}
