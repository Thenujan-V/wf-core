package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ApprovalMode;
import lk.auradot.wms.engine.definition.BreachAction;
import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.Sla;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class SlaTest {

    private final Fixture fx = new Fixture();

    private CompiledProcess withSla(Fixture fixture, Sla sla) {
        return fixture.compile(def("sla", List.of(start("s"),
                        task("review", user("carol"), ApprovalMode.SINGLE, null, sla, "APPROVE", "REJECT"),
                        end("approved", "APPROVED"), end("rejected", "REJECTED")),
                List.of(flow("s", "review"), outcome("review", "APPROVE", "approved"),
                        outcome("review", "REJECT", "rejected"))));
    }

    private static Job job(InstanceState s, JobType type) {
        return s.jobs().stream().filter(j -> j.type() == type).findFirst().orElseThrow();
    }

    @Test
    void schedulesReminderAndBreachFromTheDueTime() {
        CompiledProcess p = withSla(fx, new Sla(Duration.ofHours(48), false, 50, BreachAction.NOTIFY, null));
        InstanceState s = fx.engine.start(p, "x", Map.of(), null);
        Instant now = fx.clock.instant();

        assertThat(s.openTasks().getFirst().dueAt()).isEqualTo(now.plus(Duration.ofHours(48)));
        assertThat(job(s, JobType.SLA_REMINDER).dueAt()).isEqualTo(now.plus(Duration.ofHours(24)));
        assertThat(job(s, JobType.SLA_BREACH).dueAt()).isEqualTo(now.plus(Duration.ofHours(48)));

        s.drainEvents();
        fx.engine.fireJob(p, s, job(s, JobType.SLA_REMINDER).id());
        fx.engine.fireJob(p, s, job(s, JobType.SLA_BREACH).id());

        String taskId = s.openTasks().getFirst().id();
        assertThat(s.drainEvents()).containsExactly(
                new EngineEvent.TaskReminderDue(taskId, "carol"),
                new EngineEvent.TaskOverdue(taskId, "carol", BreachAction.NOTIFY));
    }

    @Test
    void completingTheTaskCancelsItsSlaJobs() {
        CompiledProcess p = withSla(fx, new Sla(Duration.ofHours(8), false, 50, BreachAction.AUTO_COMPLETE, "REJECT"));
        InstanceState s = fx.engine.start(p, "x", Map.of(), null);

        fx.engine.completeTask(p, s, s.openTasks().getFirst().id(), "carol", "APPROVE", Map.of());
        fx.engine.fireJob(p, s, job(s, JobType.SLA_BREACH).id());

        assertThat(s.jobs()).extracting(Job::status).containsOnly(JobStatus.CANCELLED);
        assertThat(s.endStatus()).as("late breach job must not override").isEqualTo("APPROVED");
    }

    @Test
    void breachReassignsToTheManagerOrFallsBackToNotify() {
        fx.directory.manager("carol", "bob");
        CompiledProcess p = withSla(fx, new Sla(Duration.ofHours(8), false, null, BreachAction.REASSIGN_TO_MANAGER, null));
        InstanceState s = fx.engine.start(p, "x", Map.of(), null);

        fx.engine.fireJob(p, s, job(s, JobType.SLA_BREACH).id());

        Task task = s.openTasks().getFirst();
        assertThat(task.assigneeId()).isEqualTo("bob");
        assertThat(s.drainEvents()).contains(new EngineEvent.TaskReassigned(task.id(), "carol", "bob", "system"));

        Fixture noManager = new Fixture();
        CompiledProcess p2 = withSla(noManager, new Sla(Duration.ofHours(8), false, null, BreachAction.REASSIGN_TO_MANAGER, null));
        InstanceState s2 = noManager.engine.start(p2, "x", Map.of(), null);
        noManager.engine.fireJob(p2, s2, job(s2, JobType.SLA_BREACH).id());
        assertThat(s2.openTasks().getFirst().assigneeId()).isEqualTo("carol");
        assertThat(s2.drainEvents()).anySatisfy(e -> assertThat(e)
                .isEqualTo(new EngineEvent.TaskOverdue(s2.openTasks().getFirst().id(), "carol", BreachAction.NOTIFY)));
    }

    @Test
    void breachCanCompleteTheTaskAutomatically() {
        CompiledProcess p = withSla(fx, new Sla(Duration.ofHours(8), false, null, BreachAction.AUTO_COMPLETE, "REJECT"));
        InstanceState s = fx.engine.start(p, "x", Map.of(), null);

        fx.engine.fireJob(p, s, job(s, JobType.SLA_BREACH).id());

        assertThat(s.endStatus()).isEqualTo("REJECTED");
        assertThat(s.tasks().getFirst().completedBy()).isEqualTo("system");
    }

    @Test
    void businessDaySlaUsesTheWorkingCalendar() {
        Fixture business = new Fixture(new WeekendHolidayCalendar(ZoneOffset.UTC,
                Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Set.of(LocalDate.parse("2026-10-05"))), RetryPolicy.defaults());
        business.clock.set(Instant.parse("2026-10-02T15:00:00Z"));
        CompiledProcess p = withSla(business, new Sla(Duration.ofDays(2), true, null, BreachAction.NOTIFY, null));

        InstanceState s = business.engine.start(p, "x", Map.of(), null);

        assertThat(s.openTasks().getFirst().dueAt()).isEqualTo(Instant.parse("2026-10-07T15:00:00Z"));
    }
}
