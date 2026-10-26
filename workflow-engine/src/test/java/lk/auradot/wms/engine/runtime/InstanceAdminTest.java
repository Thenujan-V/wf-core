package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ApprovalMode;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.Sla;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class InstanceAdminTest {

    private final Fixture fx = new Fixture();
    private final CompiledProcess process = fx.compile(def("admin", List.of(start("s"),
                    task("review", user("alice"), ApprovalMode.SINGLE, null,
                            new Sla(Duration.ofHours(8), false, 50, BreachAction.NOTIFY, null), "OK"),
                    end("e")),
            List.of(flow("s", "review"), outcome("review", "OK", "e"))));

    private static EngineError errorOf(Runnable action) {
        try {
            action.run();
        } catch (EngineException e) {
            return e.error();
        }
        throw new AssertionError("expected an EngineException");
    }

    @Test
    void cancelEndsEverythingAndBlocksFurtherWork() {
        InstanceState s = fx.engine.start(process, "carol", Map.of(), null);
        String taskId = s.openTasks().getFirst().id();

        fx.engine.cancel(process, s, "admin");

        assertThat(s.status()).isEqualTo(InstanceStatus.CANCELLED);
        assertThat(s.endedAt()).isNotNull();
        assertThat(s.tasks()).extracting(Task::status).containsOnly(TaskStatus.CANCELLED);
        assertThat(s.jobs()).extracting(Job::status).containsOnly(JobStatus.CANCELLED);
        assertThat(s.tokens()).extracting(Token::status).containsOnly(TokenStatus.ENDED);
        assertThat(errorOf(() -> fx.engine.completeTask(process, s, taskId, "alice", "OK", Map.of())))
                .isEqualTo(EngineError.INSTANCE_NOT_ACTIVE);
        assertThat(errorOf(() -> fx.engine.cancel(process, s, "admin"))).isEqualTo(EngineError.INSTANCE_NOT_ACTIVE);
    }

    @Test
    void suspendPausesJobsAndTasksUntilResumed() {
        InstanceState s = fx.engine.start(process, "carol", Map.of(), null);
        String taskId = s.openTasks().getFirst().id();
        Job breach = s.jobs().stream().filter(j -> j.type() == JobType.SLA_BREACH).findFirst().orElseThrow();

        fx.engine.suspend(process, s, "admin");

        assertThat(s.status()).isEqualTo(InstanceStatus.SUSPENDED);
        assertThat(s.jobs()).extracting(Job::status).containsOnly(JobStatus.SUSPENDED);
        assertThat(errorOf(() -> fx.engine.completeTask(process, s, taskId, "alice", "OK", Map.of())))
                .isEqualTo(EngineError.INSTANCE_NOT_ACTIVE);
        fx.engine.fireJob(process, s, breach.id());
        assertThat(breach.status()).as("suspended jobs do not fire").isEqualTo(JobStatus.SUSPENDED);

        fx.clock.advance(Duration.ofHours(10));
        fx.engine.resume(process, s, "admin");

        assertThat(s.status()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(breach.status()).isEqualTo(JobStatus.PENDING);
        assertThat(breach.dueAt()).as("overdue jobs become due now").isEqualTo(fx.clock.instant());
        fx.engine.completeTask(process, s, taskId, "alice", "OK", Map.of());
        assertThat(s.status()).isEqualTo(InstanceStatus.COMPLETED);
    }

    @Test
    void resumeRequiresASuspendedInstance() {
        InstanceState s = fx.engine.start(process, "carol", Map.of(), null);

        assertThat(errorOf(() -> fx.engine.resume(process, s, "admin"))).isEqualTo(EngineError.INSTANCE_NOT_ACTIVE);
    }
}
