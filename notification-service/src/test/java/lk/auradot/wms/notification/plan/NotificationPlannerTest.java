package lk.auradot.wms.notification.plan;

import lk.auradot.wms.notification.FakeDirectory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPlannerTest {

    FakeDirectory directory = new FakeDirectory();
    NotificationPlanner planner = new NotificationPlanner(directory);

    @BeforeEach
    void people() {
        directory.person("alice", "Alice Admin", "alice@example.com");
        directory.person("bob", "Bob Manager", "bob@example.com");
        directory.person("carol", "Carol Clerk", "carol@example.com");
        directory.person("old", "Old Hand", "old@example.com");
        directory.disable("old");
        directory.group("g-fin", "bob", "carol", "old");
        directory.role("finance", "bob", "alice");
    }

    static Map<String, Object> workflow(String actorId, Map<String, Object> data, Object... more) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("messageId", "m-1");
        event.put("instanceId", "i-1");
        event.put("processKey", "expense-request");
        event.put("businessKey", "EXP-7");
        event.put("initiatorId", "carol");
        event.put("actorId", actorId);
        event.put("taskName", "Manager approval");
        event.put("data", data);
        for (int i = 0; i < more.length; i += 2) {
            event.put((String) more[i], more[i + 1]);
        }
        return event;
    }

    @Test
    void aTaskCreatedForSomeoneTellsThatPerson() {
        List<Planned> planned = planner.plan("workflow.task.created",
                workflow("carol", Map.of("taskId", "t-1", "assigneeId", "bob", "candidates", List.of())));

        assertThat(planned).containsExactly(new Planned("bob", Kind.TASK_ASSIGNED,
                Map.of("taskName", "Manager approval", "reference", "EXP-7", "processKey", "expense-request"), "/inbox"));
    }

    @Test
    void aTaskOfferedToGroupsAndRolesTellsEachEnabledMemberOnce() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", "t-2");
        data.put("assigneeId", null);
        data.put("candidates", List.of("group:g-fin", "role:finance"));

        List<Planned> planned = planner.plan("workflow.task.created", workflow("system", data));

        assertThat(planned).extracting(Planned::userId).containsExactlyInAnyOrder("bob", "carol", "alice");
        assertThat(planned).allSatisfy(p -> assertThat(p.kind()).isEqualTo(Kind.TASK_AVAILABLE));
    }

    @Test
    void delegationReassignmentRemindersAndBreachesTellTheRightPerson() {
        assertThat(planner.plan("workflow.task.delegated", workflow("bob", Map.of("taskId", "t-1", "fromUserId", "bob", "toUserId", "alice"))))
                .extracting(Planned::userId, Planned::kind).containsExactly(org.assertj.core.groups.Tuple.tuple("alice", Kind.TASK_ASSIGNED));
        assertThat(planner.plan("workflow.task.reassigned", workflow("alice", Map.of("taskId", "t-1", "toUserId", "bob"))))
                .extracting(Planned::userId, Planned::kind).containsExactly(org.assertj.core.groups.Tuple.tuple("bob", Kind.TASK_ASSIGNED));
        assertThat(planner.plan("workflow.task.reminder", workflow(null, Map.of("taskId", "t-1", "assigneeId", "bob"))))
                .extracting(Planned::userId, Planned::kind).containsExactly(org.assertj.core.groups.Tuple.tuple("bob", Kind.TASK_REMINDER));
        assertThat(planner.plan("workflow.task.overdue", workflow(null, Map.of("taskId", "t-1", "assigneeId", "bob", "action", "NOTIFY"))))
                .extracting(Planned::userId, Planned::kind).containsExactly(org.assertj.core.groups.Tuple.tuple("bob", Kind.TASK_OVERDUE));
        // A reminder for a task nobody holds has nobody to remind.
        Map<String, Object> unassigned = new LinkedHashMap<>();
        unassigned.put("taskId", "t-2");
        unassigned.put("assigneeId", null);
        assertThat(planner.plan("workflow.task.reminder", workflow(null, unassigned))).isEmpty();
    }

    @Test
    void theRequesterHearsHowTheirRequestEnded() {
        List<Planned> approved = planner.plan("workflow.instance.completed", workflow("bob", Map.of("endStatus", "APPROVED")));
        assertThat(approved).containsExactly(new Planned("carol", Kind.REQUEST_FINISHED,
                Map.of("reference", "EXP-7", "processKey", "expense-request", "outcome", "approved"), "/requests/i-1"));

        List<Planned> cancelled = planner.plan("workflow.instance.cancelled", workflow("alice", Map.of("actorId", "alice")));
        assertThat(cancelled).singleElement().satisfies(p -> assertThat(p.values()).containsEntry("outcome", "cancelled"));

        Map<String, Object> noKey = workflow("bob", Map.of("endStatus", "NEEDS_REWORK"), "businessKey", null);
        assertThat(planner.plan("workflow.instance.completed", noKey)).singleElement()
                .satisfies(p -> assertThat(p.values()).containsEntry("reference", "expense-request").containsEntry("outcome", "needs rework"));
    }

    @Test
    void aSharedFolderTellsThePersonItWasSharedWith() {
        Map<String, Object> event = Map.of("messageId", "m-9", "folderId", "f-1", "folderName", "Finance", "userId", "carol",
                "permission", "WRITE", "actorId", "dave");

        assertThat(planner.plan("document.folder.shared", event)).containsExactly(new Planned("carol", Kind.DOCUMENT_SHARED,
                Map.of("folderName", "Finance", "permission", "write"), "/documents/folders/f-1"));
    }

    @Test
    void nobodyIsToldAboutTheirOwnActionOrAboutEventsThatConcernNobody() {
        assertThat(planner.plan("workflow.task.created",
                workflow("bob", Map.of("taskId", "t-1", "assigneeId", "bob", "candidates", List.of())))).isEmpty();
        assertThat(planner.plan("workflow.instance.completed", workflow("carol", Map.of("endStatus", "APPROVED")))).isEmpty();
        assertThat(planner.plan("workflow.task.claimed", workflow("bob", Map.of("taskId", "t-1", "userId", "bob")))).isEmpty();
        assertThat(planner.plan("document.deleted", Map.of("messageId", "m-2", "documentId", "d-1", "actorId", "bob"))).isEmpty();
    }

    @Test
    void aProcessEmailTakesItsAddressFromTheStepOrFromTheRequestsData() {
        Map<String, Object> variables = Map.of("initiator", "carol", "form", Map.of("email", "supplier@example.org", "amount", 500));
        Map<String, Object> toAddress = workflow("system", Map.of("nodeId", "notify"),
                "email", Map.of("to", "finance@example.com", "template", "request-approved"), "variables", variables);
        Map<String, Object> toFormField = workflow("system", Map.of("nodeId", "notify"),
                "email", Map.of("to", "form.email", "template", "request-approved"), "variables", variables);
        Map<String, Object> toInitiator = workflow("system", Map.of("nodeId", "notify"),
                "email", Map.of("to", "initiator", "template", "request-approved"), "variables", variables);

        assertThat(planner.processEmail("workflow.email.requested", toAddress)).hasValueSatisfying(email -> {
            assertThat(email.to()).isEqualTo("finance@example.com");
            assertThat(email.template()).isEqualTo("request-approved");
            assertThat(email.values()).containsEntry("reference", "EXP-7").containsEntry("processKey", "expense-request")
                    .containsEntry("form.amount", "500");
        });
        assertThat(planner.processEmail("workflow.email.requested", toFormField)).hasValueSatisfying(
                email -> assertThat(email.to()).isEqualTo("supplier@example.org"));
        assertThat(planner.processEmail("workflow.email.requested", toInitiator)).hasValueSatisfying(
                email -> assertThat(email.to()).isEqualTo("carol"));
    }

    @Test
    void aProcessEmailWithNowhereToGoIsNotPlanned() {
        Map<String, Object> missing = workflow("system", Map.of("nodeId", "notify"),
                "email", Map.of("to", "form.contact", "template", "x"), "variables", Map.of("form", Map.of()));

        assertThat(planner.processEmail("workflow.email.requested", missing)).isEmpty();
        assertThat(planner.processEmail("workflow.task.created", missing)).isEmpty();
    }

    @Test
    void aRecipientCanBeWrittenWithTheDollarBraceForm() {
        Map<String, Object> variables = Map.of("initiator", "carol", "form", Map.of("email", "supplier@example.org"));
        Map<String, Object> toInitiator = workflow("system", Map.of("nodeId", "notify"),
                "email", Map.of("to", "${initiator}", "template", "request-approved"), "variables", variables);
        Map<String, Object> toFormField = workflow("system", Map.of("nodeId", "notify"),
                "email", Map.of("to", "${ form.email }", "template", "request-approved"), "variables", variables);

        assertThat(planner.processEmail("workflow.email.requested", toInitiator)).hasValueSatisfying(
                email -> assertThat(email.to()).isEqualTo("carol"));
        assertThat(planner.processEmail("workflow.email.requested", toFormField)).hasValueSatisfying(
                email -> assertThat(email.to()).isEqualTo("supplier@example.org"));
    }
}
