package lk.auradot.wms.engine.definition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class DefinitionValidatorBasicsTest {

    @Test
    void acceptsASimpleApproval() {
        assertThat(DefinitionValidator.validate(simpleApproval())).isEmpty();
    }

    @Test
    void rejectsDuplicateIdsAndDanglingFlows() {
        var def = def("p", List.of(start("s"), end("e"), end("e")),
                List.of(flow("s", "e"), flow("s", "ghost")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("DUPLICATE_NODE_ID", "EDGE_UNKNOWN_NODE");
    }

    @Test
    void requiresExactlyOneStartAndAnEnd() {
        assertThat(codes(DefinitionValidator.validate(def("p", List.of(end("e")), List.of()))))
                .containsExactly("START_COUNT");
        assertThat(codes(DefinitionValidator.validate(def("p", List.of(start("a"), start("b"), end("e")),
                List.of(flow("a", "e"), flow("b", "e")))))).contains("START_COUNT");
        assertThat(codes(DefinitionValidator.validate(def("p", List.of(start("s"), task("t", user("u"), "OK")),
                List.of(flow("s", "t"), outcome("t", "OK", "s")))))).contains("NO_END");
    }

    @Test
    void checksStartAndEndFlows() {
        var def = def("p", List.of(start("s"), task("t", user("u"), "OK"), end("e")),
                List.of(flow("s", "t"), outcome("t", "OK", "e"), flow("e", "s")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("START_INCOMING", "END_OUTGOING");
    }

    @Test
    void reportsUnreachableAndDeadEndNodes() {
        var def = def("p", List.of(start("s"), task("a", user("u"), "OK"), task("b", user("u"), "OK"),
                        task("orphan", user("u"), "OK"), end("e")),
                List.of(flow("s", "a"), outcome("a", "OK", "b"), outcome("b", "OK", "a"),
                        outcome("orphan", "OK", "e")));

        var errors = DefinitionValidator.validate(def);

        assertThat(errors).contains(
                new ValidationError("orphan", "UNREACHABLE", "This node cannot be reached from the start"));
        // "e" is only reachable through the unreachable "orphan", so nothing reachable can end: s, a and b.
        assertThat(errors.stream().filter(e -> e.code().equals("DEAD_END")).map(ValidationError::nodeId))
                .containsExactlyInAnyOrder("s", "a", "b");
    }

    @Test
    void onlyGatewaysAndTasksMayLabelFlows() {
        var def = def("p", List.of(start("s"), exclusive("x"), task("t", user("u"), "OK"), end("e")),
                List.of(when("s", "x", java.util.Map.of("var", "a", "op", "isEmpty")),
                        outcome("x", "OK", "t"), otherwise("x", "t"), outcome("t", "OK", "e")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("CONDITION_NOT_ALLOWED", "OUTCOME_NOT_ALLOWED");
    }
}
