package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.runtime.InstanceState;
import lk.auradot.wms.engine.runtime.Job;
import lk.auradot.wms.engine.runtime.JobType;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.workflow.Json;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.CompiledProcesses;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@WorkflowIntegrationTest
class EventRecorderTest {

    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired CompiledProcesses processes;
    @Autowired ProcessEngine engine;
    @Autowired InstanceStore instances;
    @Autowired EventRecorder recorder;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;
    @Autowired Json json;

    String definitionId;
    CompiledProcess process;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        definitions.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        definitionId = definitions.publish("expense-approval", "bob").id();
        process = processes.get(definitionId);
    }

    private List<String> outboxKeys() {
        return jdbc.sql("SELECT routing_key FROM outbox ORDER BY id").query(String.class).list();
    }

    @Test
    void everyEventIsHistoryButOnlyMeaningfulOnesArePublished() {
        InstanceState state = engine.start(process, "carol", Map.of("amount", 500), "EXP-2");
        instances.insert(definitionId, state);
        var events = state.drainEvents();

        recorder.record(process, state, events, "carol");

        assertThat(jdbc.sql("SELECT COUNT(*) FROM history_event WHERE instance_id = :id").param("id", state.id())
                .query(Integer.class).single()).isEqualTo(events.size());
        assertThat(outboxKeys()).containsExactly("workflow.instance.started", "workflow.task.created");
        Map<String, Object> payload = json.readMap(jdbc.sql("SELECT payload_json FROM outbox ORDER BY id LIMIT 1")
                .query(String.class).single());
        assertThat(payload).containsEntry("instanceId", state.id()).containsEntry("processKey", "expense-approval")
                .containsEntry("businessKey", "EXP-2").containsEntry("actorId", "carol").containsKey("messageId");
    }

    @Test
    void completedEmailActionsBecomeEmailRequests() {
        InstanceState state = engine.start(process, "carol", Map.of("amount", 500), null);
        instances.insert(definitionId, state);
        engine.completeTask(process, state, state.openTasks().getFirst().id(), "bob", "APPROVE", Map.of());
        Job notify = state.jobs().stream().filter(j -> j.type() == JobType.ASYNC_ACTION).findFirst().orElseThrow();
        state.drainEvents();

        engine.actionSucceeded(process, state, notify.id());
        recorder.record(process, state, state.drainEvents(), "system");

        assertThat(outboxKeys()).contains("workflow.email.requested", "workflow.instance.completed");
        Map<String, Object> email = json.readMap(jdbc.sql(
                "SELECT payload_json FROM outbox WHERE routing_key = 'workflow.email.requested'").query(String.class).single());
        assertThat((Map<String, Object>) email.get("email")).containsEntry("to", "${initiator}")
                .containsEntry("template", "expense-approved");
        assertThat((Map<String, Object>) email.get("variables")).containsEntry("initiator", "carol");
    }
}
