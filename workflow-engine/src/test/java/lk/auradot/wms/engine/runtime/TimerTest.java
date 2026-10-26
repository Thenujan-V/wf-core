package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.Node;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;

class TimerTest {

    private final Fixture fx = new Fixture();

    private CompiledProcess waitThenReview(Node timer) {
        return fx.compile(def("wait", List.of(start("s"), timer, task("review", user("alice"), "OK"), end("e")),
                List.of(flow("s", timer.id()), flow(timer.id(), "review"), outcome("review", "OK", "e"))));
    }

    private Job onlyJob(InstanceState state) {
        return state.jobs().getFirst();
    }

    @Test
    void durationTimerWaitsThenContinues() {
        CompiledProcess p = waitThenReview(timer("wait", Duration.ofHours(4)));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        Job job = onlyJob(s);
        assertThat(job.type()).isEqualTo(JobType.TIMER);
        assertThat(job.dueAt()).isEqualTo(fx.clock.instant().plus(Duration.ofHours(4)));
        assertThat(s.openTasks()).isEmpty();

        fx.engine.fireJob(p, s, job.id());

        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(s.openTasks()).extracting(Task::nodeId).containsExactly("review");
    }

    @Test
    void firingTheSameJobTwiceHasNoFurtherEffect() {
        CompiledProcess p = waitThenReview(timer("wait", Duration.ofMinutes(5)));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        String jobId = onlyJob(s).id();

        fx.engine.fireJob(p, s, jobId);
        s.drainEvents();
        fx.engine.fireJob(p, s, jobId);

        assertThat(s.drainEvents()).as("second firing records nothing").isEmpty();
        assertThat(s.status()).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(s.tasks()).singleElement().satisfies(t -> assertThat(t.isOpen()).isTrue());
    }

    @Test
    void dateTimerUsesTheFormDate() {
        CompiledProcess p = waitThenReview(timerUntil("wait", "form.resumeOn"));
        InstanceState s = fx.engine.start(p, "carol", Map.of("resumeOn", "2026-10-01"), null);

        assertThat(onlyJob(s).dueAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(onlyJob(s).status()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void missingOrInvalidDateMakesAVisibleDeadJobThatAnAdminCanRelease() {
        CompiledProcess p = waitThenReview(timerUntil("wait", "form.resumeOn"));
        for (Map<String, Object> form : List.<Map<String, Object>>of(Map.of(), Map.of("resumeOn", "next week"))) {
            InstanceState s = fx.engine.start(p, "carol", form, null);
            Job job = onlyJob(s);

            assertThat(job.status()).isEqualTo(JobStatus.DEAD);
            assertThat(job.lastError()).contains("form.resumeOn");
            assertThat(s.status()).isEqualTo(InstanceStatus.ACTIVE);
            assertThat(s.drainEvents()).anySatisfy(e -> assertThat(e).isInstanceOf(EngineEvent.JobDead.class));

            fx.engine.fireJob(p, s, job.id());
            assertThat(s.openTasks()).as("a dead job does not fire").isEmpty();

            fx.engine.retryJob(p, s, job.id(), "admin");
            assertThat(job.status()).isEqualTo(JobStatus.PENDING);
            fx.engine.fireJob(p, s, job.id());
            assertThat(s.openTasks()).extracting(Task::nodeId).containsExactly("review");
        }
    }

    @Test
    void retryOnlyAppliesToDeadJobs() {
        CompiledProcess p = waitThenReview(timer("wait", Duration.ofMinutes(5)));
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        assertThat(errorOf(() -> fx.engine.retryJob(p, s, onlyJob(s).id(), "admin"))).isEqualTo(EngineError.JOB_NOT_DEAD);
        assertThat(errorOf(() -> fx.engine.fireJob(p, s, "nope"))).isEqualTo(EngineError.JOB_NOT_FOUND);
    }

    private static EngineError errorOf(Runnable action) {
        try {
            action.run();
        } catch (EngineException e) {
            return e.error();
        }
        throw new AssertionError("expected an EngineException");
    }
}
