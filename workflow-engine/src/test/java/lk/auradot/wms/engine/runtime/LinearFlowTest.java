package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.CompiledProcess;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.simpleApproval;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LinearFlowTest {

    private final Fixture fx = new Fixture();
    private final CompiledProcess process = fx.compile(simpleApproval());

    @Test
    void startCreatesTheFirstTaskAndSetsSystemVariables() {
        fx.directory.department("carol", "Finance");

        InstanceState state = fx.engine.start(process, "carol", Map.of("amount", 500), "REQ-1");

        assertThat(state.status()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(state.processKey()).isEqualTo("simple");
        assertThat(state.businessKey()).isEqualTo("REQ-1");
        assertThat(state.variables()).containsEntry("initiator", "carol")
                .containsEntry("form", Map.of("amount", 500));
        assertThat((Map<String, Object>) state.variables().get("requester")).containsEntry("department", "Finance");
        assertThat(state.openTasks()).singleElement().satisfies(task -> {
            assertThat(task.nodeId()).isEqualTo("review");
            assertThat(task.assigneeId()).isEqualTo("alice");
            assertThat(task.status()).isEqualTo(TaskStatus.OPEN);
        });
        assertThat(state.tokens()).singleElement().extracting(Token::status).isEqualTo(TokenStatus.WAITING);
        assertThat(Fixture.eventTypes(state.drainEvents())).containsSubsequence(
                EngineEvent.InstanceStarted.class, EngineEvent.TaskCreated.class);
        assertThat(state.drainEvents()).isEmpty();
    }

    @Test
    void completingTheTaskFollowsItsOutcomeToTheEnd() {
        InstanceState state = fx.engine.start(process, "carol", Map.of(), null);
        String taskId = state.openTasks().getFirst().id();

        fx.engine.completeTask(process, state, taskId, "alice", "REJECT", Map.of("reason", "Over budget"));

        assertThat(state.status()).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(state.endStatus()).isEqualTo("REJECTED");
        assertThat(state.endedAt()).isEqualTo(fx.clock.instant());
        assertThat(state.task(taskId).orElseThrow().outcome()).isEqualTo("REJECT");
        assertThat((Map<String, Object>) state.variables().get("form")).containsEntry("reason", "Over budget");
        assertThat(state.variables()).containsEntry("nodes", Map.of("review", Map.of("outcome", "REJECT")));
        assertThat(state.tokens()).allSatisfy(t -> assertThat(t.status()).isEqualTo(TokenStatus.ENDED));
        assertThat(Fixture.eventTypes(state.drainEvents())).containsSubsequence(
                EngineEvent.TaskCompleted.class, EngineEvent.NodeDecided.class, EngineEvent.InstanceCompleted.class);
    }

    @Test
    void onlyTheAssigneeMayCompleteWithAKnownOutcome() {
        InstanceState state = fx.engine.start(process, "carol", Map.of(), null);
        String taskId = state.openTasks().getFirst().id();

        assertThatThrownBy(() -> fx.engine.completeTask(process, state, taskId, "mallory", "APPROVE", Map.of()))
                .isInstanceOfSatisfying(EngineException.class, e -> assertThat(e.error()).isEqualTo(EngineError.NOT_ASSIGNEE));
        assertThatThrownBy(() -> fx.engine.completeTask(process, state, taskId, "alice", "MAYBE", Map.of()))
                .isInstanceOfSatisfying(EngineException.class, e -> assertThat(e.error()).isEqualTo(EngineError.INVALID_OUTCOME));
        assertThatThrownBy(() -> fx.engine.completeTask(process, state, "nope", "alice", "APPROVE", Map.of()))
                .isInstanceOfSatisfying(EngineException.class, e -> assertThat(e.error()).isEqualTo(EngineError.TASK_NOT_FOUND));
    }

    @Test
    void aCompletedTaskCannotBeCompletedAgain() {
        InstanceState state = fx.engine.start(process, "carol", Map.of(), null);
        String taskId = state.openTasks().getFirst().id();
        fx.engine.completeTask(process, state, taskId, "alice", "APPROVE", Map.of());

        assertThatThrownBy(() -> fx.engine.completeTask(process, state, taskId, "alice", "APPROVE", Map.of()))
                .isInstanceOfSatisfying(EngineException.class,
                        e -> assertThat(e.error()).isIn(EngineError.TASK_NOT_OPEN, EngineError.INSTANCE_NOT_ACTIVE));
    }
}
