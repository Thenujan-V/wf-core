package lk.auradot.wms.engine.runtime;

import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.definition.CompiledProcess;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static lk.auradot.wms.engine.definition.Processes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * External actions (email, webhook) run outside engine commands: the caller reads an {@link ActionRequest},
 * performs it outside any transaction, then reports the result. Retried commands therefore never repeat
 * the side effect.
 */
class SystemActionTest {

    private final Fixture fx = new Fixture(WorkCalendar.calendarDays(), new RetryPolicy(3, Duration.ofMinutes(1), 5));

    private CompiledProcess actionThenReview(ActionType type, Map<String, Object> config) {
        return fx.compile(def("act", List.of(start("s"), action("act", type, config),
                        task("review", user("alice"), "OK"), end("e")),
                List.of(flow("s", "act"), flow("act", "review"), outcome("review", "OK", "e"))));
    }

    private static Job actionJob(InstanceState s) {
        return s.jobs().stream().filter(j -> j.type() == JobType.ASYNC_ACTION).findFirst().orElseThrow();
    }

    private static EngineError errorOf(Runnable action) {
        try {
            action.run();
        } catch (EngineException e) {
            return e.error();
        }
        throw new AssertionError("expected an EngineException");
    }

    private CompiledProcess webhook() {
        return actionThenReview(ActionType.WEBHOOK, Map.of("url", "https://hooks.example.com/x"));
    }

    @Test
    void setVariableRunsInsideTheEngineThenContinues() {
        CompiledProcess p = actionThenReview(ActionType.SET_VARIABLE, Map.of("variable", "form.status", "value", "RECEIVED"));
        InstanceState s = fx.engine.start(p, "carol", Map.of("amount", 5), null);

        Job job = actionJob(s);
        assertThat(job.dueAt()).isEqualTo(fx.clock.instant());
        assertThat(fx.engine.actionRequest(p, s, job.id())).as("nothing external to run").isEmpty();

        fx.engine.fireJob(p, s, job.id());

        assertThat((Map<String, Object>) s.variables().get("form")).containsEntry("status", "RECEIVED").containsEntry("amount", 5);
        assertThat(s.openTasks()).extracting(Task::nodeId).containsExactly("review");
    }

    @Test
    void externalActionsAreDescribedWithoutChangingState() {
        CompiledProcess p = actionThenReview(ActionType.SEND_EMAIL, Map.of("to", "${initiator}", "template", "received"));
        InstanceState s = fx.engine.start(p, "carol", Map.of("amount", 5), null);
        Job job = actionJob(s);
        s.drainEvents();

        ActionRequest request = fx.engine.actionRequest(p, s, job.id()).orElseThrow();

        assertThat(request.jobId()).isEqualTo(job.id());
        assertThat(request.nodeId()).isEqualTo("act");
        assertThat(request.type()).isEqualTo(ActionType.SEND_EMAIL);
        assertThat(request.config()).containsEntry("template", "received");
        assertThat(request.variables()).containsEntry("initiator", "carol");
        assertThat((Map<String, Object>) request.variables().get("form")).containsEntry("amount", 5);
        assertThatThrownBy(() -> ((Map<String, Object>) request.variables().get("form")).put("x", 1))
                .as("a snapshot, not the live state").isInstanceOf(UnsupportedOperationException.class);
        assertThat(s.drainEvents()).isEmpty();
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.attempts()).isZero();
        assertThat(errorOf(() -> fx.engine.fireJob(p, s, job.id()))).isEqualTo(EngineError.EXTERNAL_ACTION);
    }

    @Test
    void successContinuesExactlyOnceEvenWhenReportedTwice() {
        CompiledProcess p = webhook();
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Job job = actionJob(s);

        fx.engine.actionSucceeded(p, s, job.id());
        s.drainEvents();
        fx.engine.actionSucceeded(p, s, job.id());

        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(s.drainEvents()).isEmpty();
        assertThat(s.tasks()).extracting(Task::nodeId).containsExactly("review");
        assertThat(fx.engine.actionRequest(p, s, job.id())).isEmpty();
    }

    @Test
    void retryableFailuresBackOffThenDieAndCanBeRetried() {
        CompiledProcess p = webhook();
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);
        Job job = actionJob(s);

        fx.engine.actionFailed(p, s, job.id(), "webhook returned 503", true);
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.dueAt()).isEqualTo(fx.clock.instant().plus(Duration.ofMinutes(1)));
        fx.engine.actionFailed(p, s, job.id(), "webhook returned 503", true);
        assertThat(job.dueAt()).isEqualTo(fx.clock.instant().plus(Duration.ofMinutes(5)));
        fx.engine.actionFailed(p, s, job.id(), "webhook returned 503", true);

        assertThat(job.status()).isEqualTo(JobStatus.DEAD);
        assertThat(job.lastError()).isEqualTo("webhook returned 503");
        assertThat(fx.engine.actionRequest(p, s, job.id())).as("dead jobs are not run").isEmpty();
        assertThat(s.openTasks()).isEmpty();

        fx.engine.retryJob(p, s, job.id(), "admin");
        fx.engine.actionSucceeded(p, s, job.id());
        assertThat(s.openTasks()).extracting(Task::nodeId).containsExactly("review");
    }

    @Test
    void nonRetryableFailureDiesImmediately() {
        CompiledProcess p = webhook();
        InstanceState s = fx.engine.start(p, "carol", Map.of(), null);

        fx.engine.actionFailed(p, s, actionJob(s).id(), "webhook returned 400", false);

        assertThat(actionJob(s).status()).isEqualTo(JobStatus.DEAD);
    }
}
