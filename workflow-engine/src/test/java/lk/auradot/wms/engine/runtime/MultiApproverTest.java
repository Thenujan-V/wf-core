package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ApprovalMode;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.definition.Sla;
import lk.auradot.wms.engine.definition.CompiledProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class MultiApproverTest {

    private final Fixture fx = new Fixture();

    @BeforeEach
    void board() {
        fx.directory.group("board", "ann", "ben", "cal", "dee", "ann");
    }

    private CompiledProcess boardVote(ApprovalMode mode, Integer percent) {
        return fx.compile(def("vote", List.of(start("s"),
                        task("vote", group("board"), mode, percent, null, "APPROVE", "REJECT", "SEND_BACK"),
                        end("approved", "APPROVED"), end("rejected", "REJECTED"), end("returned", "RETURNED")),
                List.of(flow("s", "vote"), outcome("vote", "APPROVE", "approved"), outcome("vote", "REJECT", "rejected"),
                        outcome("vote", "SEND_BACK", "returned"))));
    }

    private void vote(CompiledProcess p, InstanceState s, String user, String outcome) {
        Task task = s.openTasks().stream().filter(t -> user.equals(t.assigneeId())).findFirst().orElseThrow();
        fx.engine.completeTask(p, s, task.id(), user, outcome, Map.of());
    }

    @Test
    void createsOneTaskPerDistinctMember() {
        InstanceState s = fx.engine.start(boardVote(ApprovalMode.ALL, null), "carol", Map.of(), null);

        assertThat(s.openTasks()).extracting(Task::assigneeId).containsExactly("ann", "ben", "cal", "dee");
        assertThat(s.openTasks()).extracting(Task::activationId).containsOnly(s.openTasks().getFirst().activationId());
    }

    @Test
    void anyFirstResponseDecidesAndCancelsTheRest() {
        CompiledProcess p = boardVote(ApprovalMode.ANY, null);
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        vote(p, s, "ben", "APPROVE");

        assertThat(s.endStatus()).isEqualTo("APPROVED");
        assertThat(s.tasks()).filteredOn(t -> t.status() == TaskStatus.CANCELLED).hasSize(3);
    }

    @Test
    void allNeedsEveryApprovalButOneRejectionDecides() {
        CompiledProcess p = boardVote(ApprovalMode.ALL, null);
        InstanceState approved = fx.engine.start(p, "carol", Map.of(), null);
        vote(p, approved, "ann", "APPROVE");
        vote(p, approved, "ben", "APPROVE");
        vote(p, approved, "cal", "APPROVE");
        assertThat(approved.status()).isEqualTo(InstanceStatus.ACTIVE);
        vote(p, approved, "dee", "APPROVE");
        assertThat(approved.endStatus()).isEqualTo("APPROVED");

        InstanceState rejected = fx.engine.start(p, "carol", Map.of(), null);
        vote(p, rejected, "ann", "APPROVE");
        vote(p, rejected, "ben", "REJECT");
        assertThat(rejected.endStatus()).isEqualTo("REJECTED");
        assertThat(rejected.openTasks()).isEmpty();

        InstanceState returned = fx.engine.start(p, "carol", Map.of(), null);
        vote(p, returned, "dee", "SEND_BACK");
        assertThat(returned.endStatus()).isEqualTo("RETURNED");
    }

    @Test
    void percentApprovesOnceTheThresholdIsReached() {
        CompiledProcess p = boardVote(ApprovalMode.PERCENT, 50);
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        vote(p, s, "ann", "APPROVE");
        vote(p, s, "ben", "REJECT");
        assertThat(s.status()).isEqualTo(InstanceStatus.ACTIVE);
        vote(p, s, "cal", "APPROVE");

        assertThat(s.endStatus()).isEqualTo("APPROVED");
        assertThat(s.task(s.tasks().stream().filter(t -> "dee".equals(t.assigneeId())).findFirst().orElseThrow().id())
                .orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void percentRejectsOnceTheThresholdCanNoLongerBeReached() {
        CompiledProcess p = boardVote(ApprovalMode.PERCENT, 75);
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        vote(p, s, "ann", "REJECT");
        assertThat(s.status()).isEqualTo(InstanceStatus.ACTIVE);
        vote(p, s, "ben", "SEND_BACK");

        assertThat(s.endStatus()).isEqualTo("REJECTED");
    }

    @Test
    void aVoterCannotBeHandedASecondTaskInTheSameRound() {
        CompiledProcess p = boardVote(ApprovalMode.ALL, null);
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Task anns = s.openTasks().stream().filter(t -> "ann".equals(t.assigneeId())).findFirst().orElseThrow();
        vote(p, s, "cal", "APPROVE");

        assertThat(errorOf(() -> fx.engine.delegateTask(p, s, anns.id(), "ann", "ben"))).isEqualTo(EngineError.INVALID_DELEGATE);
        assertThat(errorOf(() -> fx.engine.reassignTask(p, s, anns.id(), "cal", "admin")))
                .as("cal already voted in this round").isEqualTo(EngineError.INVALID_DELEGATE);

        fx.engine.delegateTask(p, s, anns.id(), "ann", "erin");
        assertThat(anns.assigneeId()).isEqualTo("erin");
    }

    @Test
    void slaReassignmentNeverGivesAVoterASecondTask() {
        fx.directory.manager("ann", "ben");
        CompiledProcess p = fx.compile(def("slaVote", List.of(start("s"),
                        task("vote", group("board"), ApprovalMode.ALL, null,
                                new Sla(Duration.ofHours(8), false, null, BreachAction.REASSIGN_TO_MANAGER, null),
                                "APPROVE", "REJECT"),
                        end("approved"), end("rejected")),
                List.of(flow("s", "vote"), outcome("vote", "APPROVE", "approved"), outcome("vote", "REJECT", "rejected"))));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Task anns = s.openTasks().stream().filter(t -> "ann".equals(t.assigneeId())).findFirst().orElseThrow();
        Job breach = s.jobs().stream().filter(j -> anns.id().equals(j.taskId()) && j.type() == JobType.SLA_BREACH)
                .findFirst().orElseThrow();
        s.drainEvents();

        fx.engine.fireJob(p, s, breach.id());

        assertThat(anns.assigneeId()).isEqualTo("ann");
        assertThat(s.drainEvents()).containsExactly(new EngineEvent.TaskOverdue(anns.id(), "ann", BreachAction.NOTIFY));
    }

    private static EngineError errorOf(Runnable action) {
        try {
            action.run();
        } catch (EngineException e) {
            return e.error();
        }
        throw new AssertionError("expected an EngineException");
    }

    @Test
    void emptyGroupFallsBackToOneUnassignedTask() {
        fx.directory.group("board");
        InstanceState s = fx.engine.start(boardVote(ApprovalMode.ALL, null), "carol", Map.of(), null);

        assertThat(s.openTasks()).singleElement().satisfies(t -> assertThat(t.unassignedReason()).contains("board"));
    }
}
