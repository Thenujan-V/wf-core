package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ApprovalMode;
import lk.auradot.wms.engine.definition.Assignment;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.definition.Sla;
import lk.auradot.wms.engine.definition.CompiledProcess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssignmentTest {

    private final Fixture fx = new Fixture();

    private CompiledProcess oneTask(Assignment assignment) {
        return fx.compile(def("p", List.of(start("s"), task("t", assignment, "DONE"), end("e")),
                List.of(flow("s", "t"), outcome("t", "DONE", "e"))));
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
    void groupTaskIsClaimedByAMemberBeforeCompletion() {
        fx.directory.group("finance", "fin1", "fin2");
        CompiledProcess p = oneTask(group("finance"));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Task task = s.openTasks().getFirst();

        assertThat(task.assigneeId()).isNull();
        assertThat(task.candidates()).containsExactly("group:finance");
        assertThat(errorOf(() -> fx.engine.completeTask(p, s, task.id(), "fin1", "DONE", Map.of())))
                .isEqualTo(EngineError.TASK_NOT_CLAIMED);
        assertThat(errorOf(() -> fx.engine.claimTask(p, s, task.id(), "outsider"))).isEqualTo(EngineError.NOT_CANDIDATE);

        fx.engine.claimTask(p, s, task.id(), "fin1");
        assertThat(task.status()).isEqualTo(TaskStatus.CLAIMED);
        assertThat(errorOf(() -> fx.engine.unclaimTask(p, s, task.id(), "fin2"))).isEqualTo(EngineError.NOT_ASSIGNEE);
        fx.engine.unclaimTask(p, s, task.id(), "fin1");
        assertThat(task.assigneeId()).isNull();
        assertThat(task.status()).isEqualTo(TaskStatus.OPEN);

        fx.engine.claimTask(p, s, task.id(), "fin2");
        fx.engine.completeTask(p, s, task.id(), "fin2", "DONE", Map.of());
        assertThat(s.status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void roleTaskIsClaimableByRoleMembers() {
        fx.directory.role("approver", "ann");
        CompiledProcess p = oneTask(role("approver"));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Task task = s.openTasks().getFirst();

        assertThat(task.candidates()).containsExactly("role:approver");
        fx.engine.claimTask(p, s, task.id(), "ann");
        assertThat(task.assigneeId()).isEqualTo("ann");
    }

    @Test
    void initiatorManagerWalksUpTheChainAndStopsAtTheTop() {
        fx.directory.manager("carol", "bob").manager("bob", "alice");

        assertThat(fx.engine.start(oneTask(manager(1)), "carol", Map.of(), null).openTasks().getFirst().assigneeId())
                .isEqualTo("bob");
        assertThat(fx.engine.start(oneTask(manager(2)), "carol", Map.of(), null).openTasks().getFirst().assigneeId())
                .isEqualTo("alice");
        assertThat(fx.engine.start(oneTask(manager(5)), "carol", Map.of(), null).openTasks().getFirst().assigneeId())
                .as("chain shorter than requested: the top-most manager").isEqualTo("alice");
    }

    @Test
    void variableUserComesFromTheForm() {
        InstanceState s = fx.engine.start(oneTask(variableUser("form.approver")), "carol", Map.of("approver", "dave"), null);

        assertThat(s.openTasks().getFirst().assigneeId()).isEqualTo("dave");
    }

    @Test
    void unresolvableAssignmentsCreateVisibleUnassignedTasks() {
        fx.directory.group("empty");
        for (Assignment assignment : List.of(manager(1), group("empty"), role("nobody"), variableUser("form.missing"))) {
            CompiledProcess p = oneTask(assignment);
            InstanceState s = fx.engine.start(p, "loner", Map.of(), null);
            Task task = s.openTasks().getFirst();

            assertThat(task.assigneeId()).as(assignment.toString()).isNull();
            assertThat(task.candidates()).isEmpty();
            assertThat(task.unassignedReason()).isNotBlank();
            assertThat(s.drainEvents()).anySatisfy(e -> assertThat(e).isInstanceOf(EngineEvent.TaskUnassignable.class));
            assertThat(errorOf(() -> fx.engine.completeTask(p, s, task.id(), "loner", "DONE", Map.of())))
                    .isEqualTo(EngineError.TASK_NOT_CLAIMED);

            fx.engine.reassignTask(p, s, task.id(), "helpdesk", "admin");
            assertThat(task.unassignedReason()).isNull();
            fx.engine.completeTask(p, s, task.id(), "helpdesk", "DONE", Map.of());
            assertThat(s.status()).isEqualTo(InstanceStatus.COMPLETED);
        }
    }

    @Test
    void idsThatNameNoActiveUserMakeTheTaskUnassigned() {
        fx.directory.inactive("Dave Perera", "leaver");
        for (var assignment : List.of(variableUser("form.approver"), user("leaver"))) {
            CompiledProcess p = oneTask(assignment);
            InstanceState s = fx.engine.start(p, "carol", Map.of("approver", "Dave Perera"), null);
            Task task = s.openTasks().getFirst();

            assertThat(task.assigneeId()).as(assignment.toString()).isNull();
            assertThat(task.unassignedReason()).isNotBlank();
            assertThat(s.drainEvents()).anySatisfy(e -> assertThat(e).isInstanceOf(EngineEvent.TaskUnassignable.class));
        }
    }

    @Test
    void tasksCannotBeDelegatedOrReassignedToInactiveUsers() {
        fx.directory.inactive("leaver");
        CompiledProcess p = oneTask(user("alice"));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Task task = s.openTasks().getFirst();

        assertThat(errorOf(() -> fx.engine.delegateTask(p, s, task.id(), "alice", "leaver"))).isEqualTo(EngineError.INVALID_DELEGATE);
        assertThat(errorOf(() -> fx.engine.reassignTask(p, s, task.id(), "leaver", "admin"))).isEqualTo(EngineError.INVALID_DELEGATE);
        assertThat(task.assigneeId()).isEqualTo("alice");
    }

    @Test
    void slaReassignmentSkipsAnInactiveManager() {
        fx.directory.manager("alice", "leaver").inactive("leaver");
        CompiledProcess p = fx.compile(def("p", List.of(start("s"),
                        task("t", user("alice"), ApprovalMode.SINGLE, null,
                                new Sla(Duration.ofHours(1), false, null, BreachAction.REASSIGN_TO_MANAGER, null), "DONE"),
                        end("e")),
                List.of(flow("s", "t"), outcome("t", "DONE", "e"))));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Job breach = s.jobs().stream().filter(j -> j.type() == JobType.SLA_BREACH).findFirst().orElseThrow();

        fx.engine.fireJob(p, s, breach.id());

        assertThat(s.openTasks().getFirst().assigneeId()).isEqualTo("alice");
    }

    @Test
    void assigneeCanDelegateToSomeoneElse() {
        CompiledProcess p = oneTask(user("alice"));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Task task = s.openTasks().getFirst();

        assertThat(errorOf(() -> fx.engine.delegateTask(p, s, task.id(), "bob", "erin"))).isEqualTo(EngineError.NOT_ASSIGNEE);
        assertThat(errorOf(() -> fx.engine.delegateTask(p, s, task.id(), "alice", "alice"))).isEqualTo(EngineError.INVALID_DELEGATE);
        assertThat(errorOf(() -> fx.engine.delegateTask(p, s, task.id(), "alice", " "))).isEqualTo(EngineError.INVALID_DELEGATE);

        fx.engine.delegateTask(p, s, task.id(), "alice", "erin");

        assertThat(task.assigneeId()).isEqualTo("erin");
        assertThat(s.drainEvents()).contains(new EngineEvent.TaskDelegated(task.id(), "alice", "erin"));
        assertThatThrownBy(() -> fx.engine.completeTask(p, s, task.id(), "alice", "DONE", Map.of()))
                .isInstanceOf(EngineException.class);
    }
}
