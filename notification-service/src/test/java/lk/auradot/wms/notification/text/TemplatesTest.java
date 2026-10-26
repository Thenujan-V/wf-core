package lk.auradot.wms.notification.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemplatesTest {

    @Test
    void fillsInThePlaceholdersAndLeavesUnknownOnesEmpty() {
        Templates templates = new Templates("");

        assertThat(templates.render("TASK_ASSIGNED.title", Map.of("taskName", "Manager approval")))
                .isEqualTo("New task: Manager approval");
        assertThat(templates.render("TASK_ASSIGNED.title", Map.of())).isEqualTo("New task: ");
    }

    @Test
    void aCustomersOwnFileReplacesOnlyTheTextsItNames(@TempDir Path dir) throws Exception {
        Path own = dir.resolve("acme.properties");
        Files.writeString(own, "TASK_ASSIGNED.title=Acme: please handle {{taskName}}\n");

        Templates templates = new Templates(own.toString());

        assertThat(templates.render("TASK_ASSIGNED.title", Map.of("taskName", "Review"))).isEqualTo("Acme: please handle Review");
        assertThat(templates.render("TASK_REMINDER.title", Map.of("taskName", "Review"))).isEqualTo("Reminder: Review");
    }

    @Test
    void knowsWhichTextsExistAndRefusesToInventOne() {
        Templates templates = new Templates("");

        assertThat(templates.has("process.default.subject")).isTrue();
        assertThat(templates.has("process.no-such-template.subject")).isFalse();
        assertThatThrownBy(() -> templates.render("NO_SUCH.title", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingCustomerFileIsAStartUpErrorNotASilentFallback() {
        assertThatThrownBy(() -> new Templates("/no/such/file.properties")).isInstanceOf(IllegalStateException.class);
    }
}
