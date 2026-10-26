package lk.auradot.wms.audit.entry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuditEventsTest {

    static final Instant RECEIVED = Instant.parse("2026-10-01T10:00:00Z");

    static Map<String, Object> event(Object... keysAndValues) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("messageId", "m-1");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            event.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return event;
    }

    static NewAuditEntry entry(String routingKey, Map<String, Object> event) {
        return AuditEvents.entry(routingKey, event, "corr-1", RECEIVED).orElseThrow();
    }

    @Test
    void taskEventsAreAboutTheTask() {
        Map<String, Object> event = event("instanceId", "i-1", "actorId", "bob", "occurredAt", "2026-10-01T09:59:58Z",
                "data", Map.of("taskId", "t-1", "outcome", "APPROVE"));

        NewAuditEntry entry = entry("workflow.task.completed", event);

        assertThat(entry.action()).isEqualTo("workflow.task.completed");
        assertThat(entry.actorId()).isEqualTo("bob");
        assertThat(entry.entityType()).isEqualTo("TASK");
        assertThat(entry.entityId()).isEqualTo("t-1");
        assertThat(entry.occurredAt()).isEqualTo(Instant.parse("2026-10-01T09:59:58Z"));
        assertThat(entry.correlationId()).isEqualTo("corr-1");
        assertThat(entry.data()).isEqualTo(event);
    }

    @Test
    void otherWorkflowEventsAreAboutTheRequestAndJobEventsAboutTheJob() {
        for (String key : new String[] {"workflow.instance.started", "workflow.files.submitted", "workflow.email.requested"}) {
            NewAuditEntry entry = entry(key, event("instanceId", "i-1"));
            assertThat(entry.entityType()).as(key).isEqualTo("INSTANCE");
            assertThat(entry.entityId()).as(key).isEqualTo("i-1");
        }
        NewAuditEntry job = entry("workflow.job.dead", event("instanceId", "i-1", "data", Map.of("jobId", "j-1")));
        assertThat(job.entityType()).isEqualTo("JOB");
        assertThat(job.entityId()).isEqualTo("j-1");
    }

    @Test
    void documentEventsAreAboutTheDocumentAndTakeTheTimeTheyArrived() {
        NewAuditEntry entry = entry("document.version.uploaded", event("documentId", "d-1", "actorId", "carol"));

        assertThat(entry.entityType()).isEqualTo("DOCUMENT");
        assertThat(entry.entityId()).isEqualTo("d-1");
        assertThat(entry.occurredAt()).isEqualTo(RECEIVED);
    }

    @Test
    void eventsOfAnUnknownKindAreStillRecorded() {
        NewAuditEntry entry = entry("identity.user.updated", event("actorId", "alice"));

        assertThat(entry.entityType()).isEqualTo("IDENTITY");
        assertThat(entry.entityId()).isNull();
        assertThat(entry.actorId()).isEqualTo("alice");
    }

    @Test
    void eventsWithNoPersonBehindThemHaveNoActor() {
        assertThat(entry("workflow.task.reminder", event("instanceId", "i-1", "data", Map.of("taskId", "t-1"))).actorId()).isNull();
    }

    @Test
    void anEventWithoutAMessageIdCannotBeRecordedAndAMissingRoutingKeyFallsBackToTheType() {
        Map<String, Object> noId = new LinkedHashMap<>(Map.of("instanceId", "i-1"));
        assertThat(AuditEvents.entry("workflow.instance.started", noId, null, RECEIVED)).isEmpty();
        assertThat(AuditEvents.entry(null, event("instanceId", "i-1"), null, RECEIVED)).isEmpty();

        NewAuditEntry typed = AuditEvents.entry(null, event("type", "workflow.instance.started", "instanceId", "i-1"), null, RECEIVED)
                .orElseThrow();
        assertThat(typed.action()).isEqualTo("workflow.instance.started");
    }

    @Test
    void overlongValuesAreCutRatherThanRefused() {
        NewAuditEntry entry = entry("document.deleted", event("documentId", "d".repeat(100), "actorId", "a".repeat(300),
                "occurredAt", "not a time"));

        assertThat(entry.entityId()).hasSize(64);
        assertThat(entry.actorId()).hasSize(255);
        assertThat(entry.occurredAt()).isEqualTo(RECEIVED);
    }
}
