package lk.auradot.wms.engine.definition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelBlocksTest {

    /** start → split → [legal, finance] → join → end */
    static ProcessDefinition twoBranches() {
        return def("par", List.of(start("s"), parallel("split"), task("legal", user("lara"), "OK"),
                        task("finance", user("fin"), "OK"), parallel("join"), end("e")),
                List.of(flow("s", "split"), flow("split", "legal"), flow("split", "finance"),
                        outcome("legal", "OK", "join"), outcome("finance", "OK", "join"), flow("join", "e")));
    }

    @Test
    void compilesMatchingSplitAndJoin() {
        CompiledProcess process = ProcessCompiler.compile(twoBranches());

        assertThat(process.isSplit("split")).isTrue();
        assertThat(process.isJoin("join")).isTrue();
        assertThat(process.joinFor("split")).isEqualTo("join");
        assertThat(process.start().id()).isEqualTo("s");
        assertThat(process.outgoing("split")).extracting(Edge::target).containsExactly("legal", "finance");
    }

    @Test
    void matchesNestedBlocks() {
        var def = def("nested", List.of(start("s"), parallel("outer"), task("a", user("u"), "OK"), parallel("inner"),
                        task("b", user("u"), "OK"), task("c", user("u"), "OK"), parallel("innerJoin"),
                        parallel("outerJoin"), end("e")),
                List.of(flow("s", "outer"), flow("outer", "a"), flow("outer", "inner"), flow("inner", "b"),
                        flow("inner", "c"), outcome("b", "OK", "innerJoin"), outcome("c", "OK", "innerJoin"),
                        flow("innerJoin", "outerJoin"), outcome("a", "OK", "outerJoin"), flow("outerJoin", "e")));

        CompiledProcess process = ProcessCompiler.compile(def);

        assertThat(process.joinFor("outer")).isEqualTo("outerJoin");
        assertThat(process.joinFor("inner")).isEqualTo("innerJoin");
    }

    @Test
    void allowsExclusiveChoicesAndSendBackInsideABranch() {
        var def = def("branchLoop", List.of(start("s"), parallel("split"), task("review", user("u"), "APPROVE", "SEND_BACK"),
                        task("fix", user("u"), "DONE"), task("other", user("u"), "OK"), parallel("join"), end("e")),
                List.of(flow("s", "split"), flow("split", "review"), flow("split", "other"),
                        outcome("review", "SEND_BACK", "fix"), outcome("fix", "DONE", "review"),
                        outcome("review", "APPROVE", "join"), outcome("other", "OK", "join"), flow("join", "e")));

        assertThat(DefinitionValidator.validate(def)).isEmpty();
    }

    @Test
    void rejectsBranchesThatNeverJoin() {
        var def = def("noJoin", List.of(start("s"), parallel("split"), task("a", user("u"), "OK"),
                        task("b", user("u"), "OK"), end("e1"), end("e2")),
                List.of(flow("s", "split"), flow("split", "a"), flow("split", "b"),
                        outcome("a", "OK", "e1"), outcome("b", "OK", "e2")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("PARALLEL_NO_JOIN");
    }

    @Test
    void rejectsFlowsEnteringABranchFromOutside() {
        var def = def("entry", List.of(start("s"), exclusive("x"), parallel("split"), task("a", user("u"), "OK"),
                        task("b", user("u"), "OK"), parallel("join"), end("e")),
                List.of(flow("s", "x"), when("x", "b", Map.of("var", "skip", "op", "eq", "value", true)),
                        otherwise("x", "split"), flow("split", "a"), flow("split", "b"),
                        outcome("a", "OK", "join"), outcome("b", "OK", "join"), flow("join", "e")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("PARALLEL_BLOCK_ENTRY");
    }

    @Test
    void rejectsSendBackFromInsideABranchToBeforeTheSplit() {
        var def = def("loopOut", List.of(start("s"), task("prepare", user("u"), "DONE"), parallel("split"),
                        task("a", user("u"), "OK", "SEND_BACK"), task("b", user("u"), "OK"), parallel("join"), end("e")),
                List.of(flow("s", "prepare"), outcome("prepare", "DONE", "split"), flow("split", "a"), flow("split", "b"),
                        outcome("a", "SEND_BACK", "prepare"), outcome("a", "OK", "join"),
                        outcome("b", "OK", "join"), flow("join", "e")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("PARALLEL_LOOP_THROUGH_SPLIT");
    }

    @Test
    void rejectsAJoinWithoutASplit() {
        var def = def("joinOnly", List.of(start("s"), exclusive("x"), task("a", user("u"), "OK"),
                        task("b", user("u"), "OK"), parallel("join"), end("e")),
                List.of(flow("s", "x"), when("x", "a", Map.of("var", "k", "op", "isEmpty")), otherwise("x", "b"),
                        outcome("a", "OK", "join"), outcome("b", "OK", "join"), flow("join", "e")));

        assertThat(codes(DefinitionValidator.validate(def))).contains("PARALLEL_JOIN_UNMATCHED");
    }

    @Test
    void compilerRejectsInvalidDefinitions() {
        var invalid = def("bad", List.of(start("s")), List.of());

        assertThatThrownBy(() -> ProcessCompiler.compile(invalid))
                .isInstanceOfSatisfying(InvalidDefinitionException.class,
                        e -> assertThat(codes(e.errors())).contains("NO_END"));
    }
}
