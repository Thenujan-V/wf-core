package lk.auradot.wms.form.definition;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.form.FormIntegrationTest;
import lk.auradot.wms.form.TestDb;
import lk.auradot.wms.form.TestForms;
import lk.auradot.wms.form.model.FieldType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@FormIntegrationTest
class FormStoreTest {

    @Autowired JdbcClient jdbc;
    @Autowired FormStore store;
    @Autowired CompiledForms forms;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
    }

    @Test
    void draftsAreSavedAndReplaced() {
        store.saveDraft("expense-claim", "Claim", TestForms.load("expense-claim.json"), "bob");
        store.saveDraft("expense-claim", "Expense claim", TestForms.load("expense-claim.json"), "bob");

        FormDraft draft = store.draft("expense-claim").orElseThrow();
        assertThat(draft.name()).isEqualTo("Expense claim");
        assertThat((List<?>) draft.definition().get("fields")).hasSize(6);
    }

    @Test
    void publishingCreatesVersionsThatStayUsableAfterRetiring() {
        store.saveDraft("expense-claim", "Expense claim", TestForms.load("expense-claim.json"), "bob");
        StoredForm v1 = store.publish("expense-claim", "bob");
        StoredForm v2 = store.publish("expense-claim", "alice");

        store.retire("expense-claim");

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(store.version("expense-claim", 1).orElseThrow().status()).isEqualTo("RETIRED");
        assertThat(forms.get("expense-claim", 1).fields().getFirst().type()).isEqualTo(FieldType.CURRENCY);
        assertThat(store.versions("expense-claim")).extracting(VersionSummary::version).containsExactly(2, 1);
        assertThat(store.list()).singleElement().satisfies(s -> {
            assertThat(s.latestVersion()).isEqualTo(2);
            assertThat(s.status()).isEqualTo("RETIRED");
            assertThat(s.hasDraft()).isTrue();
        });
    }

    @Test
    void invalidDraftsCannotBePublished() {
        store.saveDraft("broken", "Broken", Map.of("fields", List.of(Map.of("key", "h", "type", "heading", "label", "Hi"))), "bob");

        assertThat(store.validateDraft("broken")).extracting("code").containsExactly("FIELDS_MISSING");
        assertThatThrownBy(() -> store.publish("broken", "bob"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("FORM_DEFINITION_INVALID");
                    assertThat(e.properties()).containsKey("errors");
                });
        assertThatThrownBy(() -> store.publish("missing", "bob"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("FORM_DRAFT_NOT_FOUND"));
        assertThatThrownBy(() -> forms.get("broken", 1))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("FORM_VERSION_NOT_FOUND"));
        assertThatThrownBy(() -> store.retire("broken"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("FORM_NOT_FOUND"));
    }
}
