package lk.auradot.wms.notification.events;

import lk.auradot.wms.notification.FakeDirectory;
import lk.auradot.wms.notification.NotificationIntegrationTest;
import lk.auradot.wms.notification.RecordingLivePush;
import lk.auradot.wms.notification.TestDb;
import lk.auradot.wms.notification.directory.DirectoryUnavailableException;
import lk.auradot.wms.notification.plan.Kind;
import lk.auradot.wms.notification.preference.Preference;
import lk.auradot.wms.notification.preference.PreferenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec §8: events become in-app notifications and emails, honouring each person's preferences. */
@NotificationIntegrationTest
class NotificationListenerTest {

    @Autowired NotificationListener listener;
    @Autowired PreferenceStore preferences;
    @Autowired FakeDirectory directory;
    @Autowired RecordingLivePush push;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void reset() {
        push.reset();
        TestDb.clear(jdbc);
        directory.reset();
        directory.person("alice", "Alice Admin", "alice@example.com");
        directory.person("bob", "Bob Manager", "bob@example.com");
        directory.person("carol", "Carol Clerk", "carol@example.com");
        directory.group("g-fin", "bob", "carol");
        directory.role("finance", "bob", "alice");
    }

    Map<String, Object> taskCreated(String messageId, String assigneeId, List<String> candidates) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", "t-1");
        data.put("assigneeId", assigneeId);
        data.put("candidates", candidates);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("messageId", messageId);
        event.put("instanceId", "i-1");
        event.put("processKey", "expense-request");
        event.put("businessKey", "EXP-7");
        event.put("initiatorId", "carol");
        event.put("actorId", "carol");
        event.put("taskName", "Manager approval");
        event.put("data", data);
        return event;
    }

    void deliver(String routingKey, Map<String, Object> event) {
        listener.onEvent(message(routingKey, json.writeValueAsBytes(event)));
    }

    Message message(String routingKey, byte[] body) {
        return MessageBuilder.withBody(body).setReceivedRoutingKey(routingKey).build();
    }

    List<Map<String, Object>> notifications(String userId) {
        return jdbc.sql("SELECT kind, title, body, link, read_at FROM notification WHERE user_id = :u ORDER BY created_at, id")
                .param("u", userId).query().listOfRows();
    }

    List<Map<String, Object>> emails() {
        return jdbc.sql("SELECT to_address, subject, body FROM email_message ORDER BY id").query().listOfRows();
    }

    @Test
    void aNewTaskTellsItsAssigneeInTheAppAndByEmail() {
        deliver("workflow.task.created", taskCreated("m-1", "bob", List.of()));

        assertThat(notifications("bob")).singleElement().satisfies(n -> {
            assertThat(n).containsEntry("kind", "TASK_ASSIGNED").containsEntry("title", "New task: Manager approval")
                    .containsEntry("body", "You have a new task \"Manager approval\" for EXP-7.").containsEntry("link", "/inbox");
            assertThat(n.get("read_at")).isNull();
        });
        assertThat(emails()).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("to_address", "bob@example.com").containsEntry("subject", "New task: Manager approval");
            assertThat((String) e.get("body")).contains("You have a new task").endsWith("Open it: http://localhost:4200/inbox");
        });
        assertThat(notifications("carol")).isEmpty();
    }

    @Test
    void aTaskOfferedToPoolsTellsEachMemberOnceAndSendsNoEmailByDefault() {
        deliver("workflow.task.created", taskCreated("m-2", null, List.of("group:g-fin", "role:finance")));

        assertThat(notifications("bob")).singleElement().satisfies(n -> assertThat(n).containsEntry("kind", "TASK_AVAILABLE"));
        assertThat(notifications("alice")).hasSize(1);
        assertThat(notifications("carol")).isEmpty(); // carol started it: nobody is told about their own action
        assertThat(emails()).isEmpty();
    }

    @Test
    void aPersonsPreferencesDecideWhatTheyGet() {
        preferences.replace("bob", List.of(new Preference(Kind.TASK_ASSIGNED, false, false)));
        preferences.replace("alice", List.of(new Preference(Kind.TASK_AVAILABLE, false, true)));

        deliver("workflow.task.created", taskCreated("m-3", "bob", List.of()));
        deliver("workflow.task.created", taskCreated("m-4", null, List.of("role:finance")));

        assertThat(notifications("bob")).singleElement().satisfies(n -> assertThat(n).containsEntry("kind", "TASK_AVAILABLE"));
        assertThat(notifications("alice")).isEmpty();
        assertThat(emails()).extracting(e -> e.get("to_address")).containsExactly("alice@example.com");
    }

    @Test
    void theSameEventTwiceTellsPeopleOnce() {
        deliver("workflow.task.created", taskCreated("m-5", "bob", List.of()));
        deliver("workflow.task.created", taskCreated("m-5", "bob", List.of()));

        assertThat(notifications("bob")).hasSize(1);
        assertThat(emails()).hasSize(1);
    }

    @Test
    void whenThePeopleDirectoryIsDownNothingIsLost() {
        directory.down(true);
        assertThatThrownBy(() -> deliver("workflow.task.created", taskCreated("m-6", "bob", List.of())))
                .isInstanceOf(DirectoryUnavailableException.class);
        assertThat(notifications("bob")).isEmpty();

        directory.down(false);
        deliver("workflow.task.created", taskCreated("m-6", "bob", List.of())); // the broker delivers it again
        assertThat(notifications("bob")).hasSize(1);
    }

    @Test
    void someoneWithoutAnAddressOrWhoIsDisabledIsToldInTheAppOnly() {
        directory.person("erin", "Erin Intern", null);
        directory.person("old", "Old Hand", "old@example.com");
        directory.disable("old");

        deliver("workflow.task.created", taskCreated("m-7", "erin", List.of()));
        deliver("workflow.task.created", taskCreated("m-8", "old", List.of()));
        deliver("workflow.task.created", taskCreated("m-9", "ghost", List.of())); // nobody has this id

        assertThat(notifications("erin")).hasSize(1);
        assertThat(notifications("old")).hasSize(1);
        assertThat(notifications("ghost")).isEmpty();
        assertThat(emails()).isEmpty();
    }

    @Test
    void messagesThatAreNotEventsAreDropped() {
        listener.onEvent(message("workflow.task.created", "not json".getBytes(StandardCharsets.UTF_8)));
        listener.onEvent(message("workflow.task.created", "null".getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> noId = taskCreated("m-10", "bob", List.of());
        noId.remove("messageId");
        deliver("workflow.task.created", noId);

        assertThat(notifications("bob")).isEmpty();
    }

    @Test
    void whatIsStoredInTheAppIsPushedLiveOnceItIsStored() {
        deliver("workflow.task.created", taskCreated("m-20", "bob", List.of()));

        assertThat(push.pushed()).singleElement().satisfies(p -> {
            assertThat(p.userId()).isEqualTo("bob");
            assertThat(p.notification().kind()).isEqualTo("TASK_ASSIGNED");
            assertThat(p.notification().title()).isEqualTo("New task: Manager approval");
            assertThat(p.notification().link()).isEqualTo("/inbox");
            assertThat(p.notification().readAt()).isNull();
            assertThat(jdbc.sql("SELECT COUNT(*) FROM notification WHERE id = :id AND user_id = 'bob'")
                    .param("id", p.notification().id()).query(Long.class).single()).isEqualTo(1);
        });
    }

    @Test
    void nothingIsPushedForAnEmailOnlyChoiceOrForARepeatedEvent() {
        preferences.replace("bob", List.of(new Preference(Kind.TASK_ASSIGNED, false, true)));

        deliver("workflow.task.created", taskCreated("m-21", "bob", List.of()));
        deliver("workflow.task.created", taskCreated("m-22", "alice", List.of()));
        deliver("workflow.task.created", taskCreated("m-22", "alice", List.of()));

        assertThat(push.pushed()).extracting(RecordingLivePush.Pushed::userId).containsExactly("alice");
    }

    @Test
    void aBrokenLivePushLosesNothingAndFailsNothing() {
        push.failing(true);

        deliver("workflow.task.created", taskCreated("m-23", "bob", List.of()));

        assertThat(notifications("bob")).hasSize(1);
        assertThat(emails()).hasSize(1);
        push.failing(false);
        deliver("workflow.task.created", taskCreated("m-23", "bob", List.of())); // already handled: not tried again
        assertThat(notifications("bob")).hasSize(1);
    }
}
