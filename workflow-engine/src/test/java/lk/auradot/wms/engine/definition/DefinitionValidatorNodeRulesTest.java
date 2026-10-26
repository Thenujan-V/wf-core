package lk.auradot.wms.engine.definition;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class DefinitionValidatorNodeRulesTest {

    private static final Map<String, Object> AMOUNT_BIG = Map.of("var", "form.amount", "op", "gt", "value", 1000);

    private static List<String> validateTask(UserTaskNode task, List<Edge> taskEdges) {
        var edges = new java.util.ArrayList<>(List.of(flow("s", task.id())));
        edges.addAll(taskEdges);
        return codes(DefinitionValidator.validate(def("p", List.of(start("s"), task, end("e"), end("e2")), edges)));
    }

    @Test
    void exclusiveGatewayNeedsExactlyOneDefaultAndConditionsElsewhere() {
        var noDefault = def("p", List.of(start("s"), exclusive("x"), end("e")),
                List.of(flow("s", "x"), when("x", "e", AMOUNT_BIG)));
        var twoDefaults = def("p", List.of(start("s"), exclusive("x"), end("e"), end("f")),
                List.of(flow("s", "x"), otherwise("x", "e"), otherwise("x", "f")));
        var missingCondition = def("p", List.of(start("s"), exclusive("x"), end("e"), end("f")),
                List.of(flow("s", "x"), flow("x", "e"), otherwise("x", "f")));

        assertThat(codes(DefinitionValidator.validate(noDefault))).contains("EXCLUSIVE_NO_DEFAULT");
        assertThat(codes(DefinitionValidator.validate(twoDefaults))).contains("EXCLUSIVE_MULTIPLE_DEFAULTS");
        assertThat(codes(DefinitionValidator.validate(missingCondition))).contains("EXCLUSIVE_MISSING_CONDITION");
    }

    @Test
    void userTaskOutcomesMustMatchItsFlowsOneToOne() {
        assertThat(validateTask(task("t", user("u")), List.of(flow("t", "e")))).contains("TASK_NO_OUTCOMES");
        assertThat(validateTask(task("t", user("u"), "OK", "OK"), List.of(outcome("t", "OK", "e"))))
                .contains("TASK_DUPLICATE_OUTCOME");
        assertThat(validateTask(task("t", user("u"), "OK", "NO"),
                List.of(outcome("t", "OK", "e"), flow("t", "e2")))).contains("TASK_EDGE_WITHOUT_OUTCOME");
        assertThat(validateTask(task("t", user("u"), "OK"),
                List.of(outcome("t", "OK", "e"), outcome("t", "MAYBE", "e2")))).contains("TASK_UNKNOWN_OUTCOME");
        assertThat(validateTask(task("t", user("u"), "OK", "NO"), List.of(outcome("t", "OK", "e"))))
                .contains("TASK_OUTCOME_WITHOUT_EDGE");
        assertThat(validateTask(task("t", user("u"), "OK"),
                List.of(outcome("t", "OK", "e"), new Edge("dup", "t", "e2", null, false, "OK"))))
                .contains("TASK_DUPLICATE_OUTCOME_EDGE");
    }

    @Test
    void userTaskAssignmentAndApprovalModeRules() {
        List<Edge> approveReject = List.of(outcome("t", "APPROVE", "e"), outcome("t", "REJECT", "e2"));
        assertThat(validateTask(task("t", null, "APPROVE", "REJECT"), approveReject)).contains("TASK_NO_ASSIGNMENT");
        assertThat(validateTask(task("t", manager(0), "APPROVE", "REJECT"), approveReject))
                .contains("TASK_ASSIGNMENT_INVALID");
        assertThat(validateTask(task("t", variableUser(" "), "APPROVE", "REJECT"), approveReject))
                .contains("TASK_ASSIGNMENT_INVALID");
        assertThat(validateTask(task("t", user("u"), ApprovalMode.ALL, null, null, "APPROVE", "REJECT"), approveReject))
                .contains("TASK_MODE_NEEDS_GROUP");
        assertThat(validateTask(task("t", group("g"), ApprovalMode.ALL, null, null, "OK", "NO"),
                List.of(outcome("t", "OK", "e"), outcome("t", "NO", "e2")))).contains("TASK_MODE_NEEDS_APPROVE_REJECT");
        assertThat(validateTask(task("t", group("g"), ApprovalMode.PERCENT, 0, null, "APPROVE", "REJECT"), approveReject))
                .contains("TASK_PERCENT_RANGE");
        assertThat(validateTask(task("t", group("g"), ApprovalMode.ANY, 50, null, "APPROVE", "REJECT"), approveReject))
                .contains("TASK_PERCENT_RANGE");
        assertThat(validateTask(task("t", group("g"), ApprovalMode.PERCENT, 60, null, "APPROVE", "REJECT"), approveReject))
                .isEmpty();
    }

    @Test
    void slaRules() {
        List<Edge> approveReject = List.of(outcome("t", "APPROVE", "e"), outcome("t", "REJECT", "e2"));
        var negative = new Sla(Duration.ofHours(-1), false, null, BreachAction.NOTIFY, null);
        var partialBusinessDay = new Sla(Duration.ofHours(36), true, null, BreachAction.NOTIFY, null);
        var badReminder = new Sla(Duration.ofHours(8), false, 100, BreachAction.NOTIFY, null);
        var noAction = new Sla(Duration.ofHours(8), false, null, null, null);
        var autoWithoutOutcome = new Sla(Duration.ofHours(8), false, null, BreachAction.AUTO_COMPLETE, "MAYBE");
        var notifyWithOutcome = new Sla(Duration.ofHours(8), false, null, BreachAction.NOTIFY, "APPROVE");

        assertThat(validateTask(task("t", user("u"), ApprovalMode.SINGLE, null, negative, "APPROVE", "REJECT"), approveReject))
                .contains("SLA_DUE_IN");
        assertThat(validateTask(task("t", user("u"), ApprovalMode.SINGLE, null, partialBusinessDay, "APPROVE", "REJECT"), approveReject))
                .contains("SLA_DUE_IN");
        assertThat(validateTask(task("t", user("u"), ApprovalMode.SINGLE, null, badReminder, "APPROVE", "REJECT"), approveReject))
                .contains("SLA_REMINDER_RANGE");
        assertThat(validateTask(task("t", user("u"), ApprovalMode.SINGLE, null, noAction, "APPROVE", "REJECT"), approveReject))
                .contains("SLA_ACTION_MISSING");
        assertThat(validateTask(task("t", user("u"), ApprovalMode.SINGLE, null, autoWithoutOutcome, "APPROVE", "REJECT"), approveReject))
                .contains("SLA_AUTO_OUTCOME");
        assertThat(validateTask(task("t", user("u"), ApprovalMode.SINGLE, null, notifyWithOutcome, "APPROVE", "REJECT"), approveReject))
                .contains("SLA_AUTO_OUTCOME");
    }

    @Test
    void timerNeedsExactlyOneOfDurationOrDateVariable() {
        var both = new TimerNode("w", "w", Duration.ofHours(1), "form.date");
        var neither = new TimerNode("w", "w", null, null);
        var negative = timer("w", Duration.ofMinutes(-5));
        for (TimerNode timer : List.of(both, neither, negative)) {
            var def = def("p", List.of(start("s"), timer, end("e")), List.of(flow("s", "w"), flow("w", "e")));
            assertThat(codes(DefinitionValidator.validate(def))).as(timer.toString()).contains("TIMER_SPEC");
        }
    }

    @Test
    void systemActionConfigRules() {
        var emailWithoutTemplate = action("a", ActionType.SEND_EMAIL, Map.of("to", "${initiator}"));
        var ftpWebhook = action("a", ActionType.WEBHOOK, Map.of("url", "ftp://example.com/hook"));
        var setWithoutValue = action("a", ActionType.SET_VARIABLE, Map.of("variable", "form.status"));
        var badPath = action("a", ActionType.SET_VARIABLE, Map.of("variable", "form..status", "value", "x"));
        for (SystemActionNode action : List.of(emailWithoutTemplate, ftpWebhook, setWithoutValue, badPath)) {
            var def = def("p", List.of(start("s"), action, end("e")), List.of(flow("s", "a"), flow("a", "e")));
            assertThat(codes(DefinitionValidator.validate(def))).as(action.toString()).contains("ACTION_CONFIG");
        }
        var ok = action("a", ActionType.WEBHOOK, Map.of("url", "https://hooks.example.com/wms"));
        assertThat(DefinitionValidator.validate(def("p", List.of(start("s"), ok, end("e")),
                List.of(flow("s", "a"), flow("a", "e"))))).isEmpty();
    }

    @Test
    void waitStatesAndStartNeedExactlyOneOutgoingFlow() {
        var def = def("p", List.of(start("s"), timer("w", Duration.ofHours(1)), end("e"), end("f")),
                List.of(flow("s", "w"), flow("w", "e"), flow("w", "f")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("NODE_OUTGOING");
    }

    @Test
    void parallelGatewayMustSplitOrJoin() {
        var def = def("p", List.of(start("s"), parallel("p1"), end("e")), List.of(flow("s", "p1"), flow("p1", "e")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("PARALLEL_SHAPE");
    }

    @Test
    void rejectsLoopsWithoutAWaitState() {
        var def = def("p", List.of(start("s"), exclusive("x"), exclusive("y"), end("e")),
                List.of(flow("s", "x"), when("x", "e", AMOUNT_BIG), otherwise("x", "y"),
                        when("y", "e", AMOUNT_BIG), otherwise("y", "x")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("CYCLE_WITHOUT_WAIT");
    }

    @Test
    void acceptsSendBackLoopsThroughATask() {
        var def = def("p", List.of(start("s"), exclusive("x"), task("review", user("u"), "APPROVE", "SEND_BACK"),
                        task("fix", variableUser("initiator"), "DONE"), end("e")),
                List.of(flow("s", "x"), when("x", "review", AMOUNT_BIG), otherwise("x", "review"),
                        outcome("review", "APPROVE", "e"), outcome("review", "SEND_BACK", "fix"),
                        outcome("fix", "DONE", "x")));

        assertThat(DefinitionValidator.validate(def)).isEmpty();
    }
}
