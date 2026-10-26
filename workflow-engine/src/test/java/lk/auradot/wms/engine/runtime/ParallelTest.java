package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.CompiledProcess;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class ParallelTest {

    private final Fixture fx = new Fixture();

    private Task openTask(InstanceState state, String nodeId) {
        return state.openTasks().stream().filter(t -> t.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    private void complete(CompiledProcess p, InstanceState s, String nodeId, String outcome) {
        Task task = openTask(s, nodeId);
        fx.engine.completeTask(p, s, task.id(), task.assigneeId(), outcome, Map.of());
    }

    @Test
    void joinWaitsForEveryBranch() {
        // A task after the join makes a premature (or repeated) join visible, not just the final status.
        CompiledProcess p = fx.compile(def("par", List.of(start("s"), parallel("split"), task("legal", user("lara"), "OK"),
                        task("finance", user("fin"), "OK"), parallel("join"), task("signOff", user("boss"), "OK"),
                        end("e", "DONE")),
                List.of(flow("s", "split"), flow("split", "legal"), flow("split", "finance"),
                        outcome("legal", "OK", "join"), outcome("finance", "OK", "join"), flow("join", "signOff"),
                        outcome("signOff", "OK", "e"))));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        assertThat(s.openTasks()).extracting(Task::nodeId).containsExactlyInAnyOrder("legal", "finance");
        assertThat(s.scopes()).singleElement().satisfies(scope -> {
            assertThat(scope.joinNodeId()).isEqualTo("join");
            assertThat(scope.expected()).isEqualTo(2);
        });

        complete(p, s, "legal", "OK");
        assertThat(s.scopes().getFirst().arrived()).isEqualTo(1);
        assertThat(s.openTasks()).as("sign-off waits for both branches").extracting(Task::nodeId)
                .containsExactly("finance");

        complete(p, s, "finance", "OK");
        assertThat(s.tasks()).as("the join fires exactly once").filteredOn(t -> t.nodeId().equals("signOff")).hasSize(1);

        complete(p, s, "signOff", "OK");
        assertThat(s.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(s.endStatus()).isEqualTo("DONE");
    }

    @Test
    void nestedForksMergeInnerFirstInAnyCompletionOrder() {
        CompiledProcess p = fx.compile(def("nested", List.of(start("s"), parallel("outer"), task("a", user("u1"), "OK"),
                        parallel("inner"), task("b", user("u2"), "OK"), task("c", user("u3"), "OK"),
                        parallel("innerJoin"), parallel("outerJoin"), end("e")),
                List.of(flow("s", "outer"), flow("outer", "a"), flow("outer", "inner"), flow("inner", "b"),
                        flow("inner", "c"), outcome("b", "OK", "innerJoin"), outcome("c", "OK", "innerJoin"),
                        flow("innerJoin", "outerJoin"), outcome("a", "OK", "outerJoin"), flow("outerJoin", "e"))));

        for (List<String> order : List.of(List.of("a", "b", "c"), List.of("b", "a", "c"), List.of("c", "b", "a"))) {
            InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
            assertThat(s.openTasks()).hasSize(3);
            for (int i = 0; i < order.size(); i++) {
                complete(p, s, order.get(i), "OK");
                boolean last = i == order.size() - 1;
                assertThat(s.status()).as(order + " after " + order.get(i))
                        .isEqualTo(last ? InstanceStatus.COMPLETED : InstanceStatus.ACTIVE);
            }
        }
    }

    @Test
    void sendBackInsideABranchDoesNotReleaseTheJoin() {
        CompiledProcess p = fx.compile(def("loop", List.of(start("s"), parallel("split"),
                        task("review", user("r"), "APPROVE", "SEND_BACK"), task("fix", user("f"), "DONE"),
                        task("other", user("o"), "OK"), parallel("join"), end("e")),
                List.of(flow("s", "split"), flow("split", "review"), flow("split", "other"),
                        outcome("review", "SEND_BACK", "fix"), outcome("fix", "DONE", "review"),
                        outcome("review", "APPROVE", "join"), outcome("other", "OK", "join"), flow("join", "e"))));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        complete(p, s, "other", "OK");
        complete(p, s, "review", "SEND_BACK");
        complete(p, s, "fix", "DONE");
        assertThat(s.status()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(s.scopes().getFirst().arrived()).isEqualTo(1);

        complete(p, s, "review", "APPROVE");
        assertThat(s.status()).isEqualTo(InstanceStatus.COMPLETED);
    }
}
