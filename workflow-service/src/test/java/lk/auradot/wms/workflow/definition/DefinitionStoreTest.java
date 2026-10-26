package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.definition.UserTaskNode;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WorkflowIntegrationTest
class DefinitionStoreTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    DefinitionStore store;
    @Autowired
    CompiledProcesses processes;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
    }

    @Test
    void draftsAreSavedAndReplaced() {
        store.saveDraft("expense-approval", "Expense", TestProcesses.load("expense-approval.json"), "bob");
        store.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");

        Draft draft = store.draft("expense-approval").orElseThrow();
        assertThat(draft.name()).isEqualTo("Expense approval");
        assertThat(draft.updatedBy()).isEqualTo("bob");
        assertThat((List<?>) draft.graph().get("nodes")).hasSize(7);
    }

    @Test
    void publishingCreatesIncreasingImmutableVersions() {
        store.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");

        StoredDefinition v1 = store.publish("expense-approval", "bob");
        StoredDefinition v2 = store.publish("expense-approval", "bob");

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v1.status()).isEqualTo("PUBLISHED");
        assertThat(store.latestPublished("expense-approval").orElseThrow().id()).isEqualTo(v2.id());
        assertThat(store.version("expense-approval", 1).orElseThrow().id()).isEqualTo(v1.id());
        assertThat(store.catalog()).containsExactly(new CatalogEntry("expense-approval", "Expense approval", 2));
    }

    @Test
    void invalidDraftsCannotBePublished() {
        store.saveDraft("broken", "Broken", Map.of("nodes", List.of(Map.of("id", "s", "type", "START")),
                "edges", List.of()), "bob");

        assertThat(store.validateDraft("broken")).extracting("code").contains("NO_END");
        assertThatThrownBy(() -> store.publish("broken", "bob"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("WF_DEFINITION_INVALID");
                    assertThat(e.properties()).containsKey("errors");
                });
        assertThatThrownBy(() -> store.publish("missing", "bob"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WF_DRAFT_NOT_FOUND"));
    }

    @Test
    void retiringRemovesAProcessFromTheCatalog() {
        store.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        store.publish("expense-approval", "bob");

        store.retire("expense-approval");

        assertThat(store.catalog()).isEmpty();
        assertThat(store.latestPublished("expense-approval")).isEmpty();
        assertThat(store.list()).singleElement().satisfies(s -> {
            assertThat(s.status()).isEqualTo("RETIRED");
            assertThat(s.hasDraft()).isTrue();
        });
        assertThatThrownBy(() -> store.retire("unknown"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WF_PROCESS_NOT_FOUND"));
    }

    @Test
    void compiledProcessesAreBuiltFromPublishedVersions() {
        store.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        StoredDefinition v1 = store.publish("expense-approval", "bob");

        var process = processes.get(v1.id());

        assertThat(process).isSameAs(processes.get(v1.id()));
        assertThat(((UserTaskNode) process.node("financeApproval")).outcomes()).containsExactly("APPROVE", "REJECT");
    }
}
