package lk.auradot.wms.workflow.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lk.auradot.wms.engine.runtime.EngineEvent;
import lk.auradot.wms.engine.runtime.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Spec §10 operability: "Engine metrics include instances started/completed, task cycle time, SLA breaches and dead
 * jobs." Read through Actuator's metrics endpoint when a deployment exposes it.
 */
@Component
public class EngineMetrics {

    private static final Logger log = LoggerFactory.getLogger(EngineMetrics.class);

    private final Counter started;
    private final Counter completed;
    private final Counter cancelled;
    private final Counter breaches;
    private final Timer taskCycle;

    public EngineMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.started = Counter.builder("wms.instances.started").description("Requests started").register(registry);
        this.completed = Counter.builder("wms.instances.completed").description("Requests that reached an end").register(registry);
        this.cancelled = Counter.builder("wms.instances.cancelled").description("Requests cancelled").register(registry);
        this.breaches = Counter.builder("wms.sla.breaches").description("Tasks that went past their deadline").register(registry);
        this.taskCycle = Timer.builder("wms.tasks.cycle").description("From a task's creation to its completion").register(registry);
        Gauge.builder("wms.jobs.dead", jdbc, EngineMetrics::deadJobs).description("Jobs that used up their retries")
                .register(registry);
    }

    /**
     * Counts what the events say happened, once the transaction that recorded them has committed: a change that was
     * rolled back did not happen.
     */
    public void record(List<Task> tasks, List<EngineEvent> events, Instant now) {
        List<Runnable> updates = new ArrayList<>();
        for (EngineEvent event : events) {
            switch (event) {
                case EngineEvent.InstanceStarted _ -> updates.add(started::increment);
                case EngineEvent.InstanceCompleted _ -> updates.add(completed::increment);
                case EngineEvent.InstanceCancelled _ -> updates.add(cancelled::increment);
                case EngineEvent.TaskOverdue _ -> updates.add(breaches::increment);
                case EngineEvent.TaskCompleted done -> tasks.stream().filter(task -> task.id().equals(done.taskId())).findFirst()
                        .ifPresent(task -> {
                            Duration took = Duration.between(task.createdAt(), now);
                            updates.add(() -> taskCycle.record(took));
                        });
                default -> {
                    // not measured
                }
            }
        }
        if (updates.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runAll(updates);
                }
            });
        } else {
            runAll(updates);
        }
    }

    /**
     * The change these meters describe is already saved; a meter that fails must not turn that into an error for the
     * person who made it, nor stop the other meters.
     */
    static void runAll(List<Runnable> updates) {
        for (Runnable update : updates) {
            try {
                update.run();
            } catch (RuntimeException e) {
                log.warn("An engine metric could not be updated: {}", e.getMessage());
            }
        }
    }

    /** Asked each time the gauge is read. */
    private static double deadJobs(JdbcClient jdbc) {
        try {
            return jdbc.sql("SELECT COUNT(*) FROM job WHERE status = 'DEAD'").query(Long.class).single();
        } catch (RuntimeException e) {
            return Double.NaN; // the database cannot be asked right now
        }
    }
}
