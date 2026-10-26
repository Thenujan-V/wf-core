package lk.auradot.wms.notification.events;

import lk.auradot.wms.notification.FakeDirectory;
import lk.auradot.wms.notification.NotificationIntegrationTest;
import lk.auradot.wms.notification.TestDb;
import lk.auradot.wms.notification.plan.Kind;
import lk.auradot.wms.notification.preference.Preference;
import lk.auradot.wms.notification.preference.PreferenceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The emails a process's "send email" step asks for (workflow.email.requested). */
@NotificationIntegrationTest
class ProcessEmailTest {

    @Autowired NotificationListener listener;
    @Autowired PreferenceStore preferences;
    @Autowired FakeDirectory directory;
    @Autowired JdbcClient jdbc;
    @Autowired JsonMapper json;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        directory.person("carol", "Carol Clerk", "carol@example.com");
        directory.person("erin", "Erin Intern", null);
    }

    void requested(String messageId, String to, String template, String initiator) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("messageId", messageId);
        event.put("instanceId", "i-1");
        event.put("processKey", "expense-request");
        event.put("businessKey", "EXP-7");
        event.put("initiatorId", initiator);
        event.put("actorId", "system");
        event.put("data", Map.of("nodeId", "notify", "type", "SEND_EMAIL"));
        event.put("email", Map.of("to", to, "template", template));
        event.put("variables", Map.of("initiator", initiator, "form", Map.of("email", "supplier@example.org", "note", "N/A, see finance")));
        listener.onEvent(MessageBuilder.withBody(json.writeValueAsBytes(event)).setReceivedRoutingKey("workflow.email.requested").build());
    }

    List<Map<String, Object>> emails() {
        return jdbc.sql("SELECT to_address, subject, body FROM email_message ORDER BY id").query().listOfRows();
    }

    @Test
    void goesToTheAddressTheStepNamesWithTheTemplatesText() {
        requested("m-1", "finance@example.com", "request-approved", "carol");

        assertThat(emails()).singleElement().satisfies(e -> assertThat(e).containsEntry("to_address", "finance@example.com")
                .containsEntry("subject", "Approved: EXP-7")
                .containsEntry("body", "Your expense-request request EXP-7 has been approved."));
    }

    @Test
    void anAddressCanComeFromTheRequestsDataOrBeAPerson() {
        requested("m-2", "form.email", "request-approved", "carol");
        requested("m-3", "initiator", "request-rejected", "carol");

        assertThat(emails()).extracting(e -> e.get("to_address")).containsExactly("supplier@example.org", "carol@example.com");
        assertThat(emails().get(1)).containsEntry("subject", "Not approved: EXP-7");
    }

    @Test
    void anUnknownTemplateFallsBackToTheGeneralText() {
        requested("m-4", "finance@example.com", "no-such-template", "carol");

        assertThat(emails()).singleElement().satisfies(e -> assertThat(e).containsEntry("subject", "Update on EXP-7"));
    }

    @Test
    void anEmailWithNowhereToGoIsSkippedWithoutFailing() {
        requested("m-5", "form.contact", "request-approved", "carol"); // no such field
        requested("m-6", "initiator", "request-approved", "erin");     // a person without an address
        requested("m-7", "initiator", "request-approved", "ghost");    // nobody has this id

        assertThat(emails()).isEmpty();
    }

    @Test
    void isSentWhateverThePersonsPreferencesAndOnlyOncePerEvent() {
        preferences.replace("carol", Arrays.stream(Kind.values()).map(kind -> new Preference(kind, false, false)).toList());

        requested("m-8", "initiator", "request-approved", "carol");
        requested("m-8", "initiator", "request-approved", "carol");

        assertThat(emails()).hasSize(1);
    }

    @Test
    void textThatIsNeitherAnAddressNorAPersonsIdIsNotLookedUp() {
        requested("m-9", "form.note", "request-approved", "carol"); // the form's note says "N/A, see finance"

        assertThat(emails()).isEmpty();
        assertThat(directory.lookups()).isEmpty();
    }
}
