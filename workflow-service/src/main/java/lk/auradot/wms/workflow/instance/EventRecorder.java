package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.common.messaging.Outbox;
import lk.auradot.wms.engine.definition.ActionType;
import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.SystemActionNode;
import lk.auradot.wms.engine.runtime.EngineEvent;
import lk.auradot.wms.engine.runtime.InstanceState;
import lk.auradot.wms.workflow.Json;
import lk.auradot.wms.workflow.Times;
import lk.auradot.wms.workflow.metrics.EngineMetrics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns a command's engine events into history rows and outbox messages, inside the command's transaction. */
@Component
public class EventRecorder {

    private final JdbcClient jdbc;
    private final Json json;
    private final Outbox outbox;
    private final Clock clock;
    private final EngineMetrics metrics;

    public EventRecorder(JdbcClient jdbc, Json json, Outbox outbox, Clock clock, EngineMetrics metrics) {
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.json = json;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void record(CompiledProcess process, InstanceState state, List<EngineEvent> events, String actorId) {
        Instant now = clock.instant();
        for (EngineEvent event : events) {
            Map<String, Object> data = json.toMap(event);
            jdbc.sql("""
                            INSERT INTO history_event (instance_id, event_type, actor_id, data_json, occurred_at)
                            VALUES (:instance, :type, :actor, :data, :at)""")
                    .param("instance", state.id()).param("type", EventMapper.type(event)).param("actor", actorId)
                    .param("data", json.write(data)).param("at", Times.toDb(now))
                    .update();
            EventMapper.routingKey(event).ifPresent(key ->
                    outbox.add(key, payload(process, state, event, data, actorId, now), now));
        }
        metrics.record(state.tasks(), events, now);
    }

    /** Spec §5 file fields: once this commits, document-service moves the named staged files into the request. */
    public void filesSubmitted(InstanceState state, List<String> documentIds, String actorId) {
        if (documentIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", state.id());
        payload.put("processKey", state.processKey());
        payload.put("actorId", actorId);
        payload.put("documentIds", List.copyOf(documentIds));
        payload.put("occurredAt", now.toString());
        outbox.add("workflow.files.submitted", payload, now);
    }

    private Map<String, Object> payload(CompiledProcess process, InstanceState state, EngineEvent event,
                                        Map<String, Object> data, String actorId, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", state.id());
        payload.put("processKey", state.processKey());
        payload.put("processVersion", state.processVersion());
        payload.put("businessKey", state.businessKey());
        payload.put("initiatorId", state.initiatorId());
        payload.put("actorId", actorId);
        payload.put("occurredAt", now.toString());
        payload.put("data", data);
        // The step's name, for consumers that tell people about a task (notifications).
        if (data.get("taskId") instanceof String taskId) {
            state.tasks().stream().filter(task -> task.id().equals(taskId)).findFirst()
                    .ifPresent(task -> payload.put("taskName", task.name()));
        }
        if (event instanceof EngineEvent.ActionExecuted executed && executed.type() == ActionType.SEND_EMAIL) {
            SystemActionNode node = (SystemActionNode) process.node(executed.nodeId());
            Map<String, Object> email = new LinkedHashMap<>();
            email.put("to", node.config().get("to"));
            email.put("template", node.config().get("template"));
            payload.put("email", email);
            Map<String, Object> variables = new LinkedHashMap<>();
            for (String name : List.of("initiator", "requester", "form")) {
                variables.put(name, state.variables().get(name));
            }
            payload.put("variables", variables);
        }
        return payload;
    }
}
