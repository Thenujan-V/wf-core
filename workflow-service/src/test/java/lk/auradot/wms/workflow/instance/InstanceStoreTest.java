package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.runtime.InstanceState;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.engine.runtime.Task;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.CompiledProcesses;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import lk.auradot.wms.workflow.definition.StoredDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WorkflowIntegrationTest
class InstanceStoreTest {

    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired CompiledProcesses processes;
    @Autowired ProcessEngine engine;
    @Autowired InstanceStore instances;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    StoredDefinition expense;
    CompiledProcess process;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        definitions.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        expense = definitions.publish("expense-approval", "bob");
        process = processes.get(expense.id());
    }

    @Test
    void roundTripsEveryPartOfTheState() {
        InstanceState started = engine.start(process, "carol", Map.of("amount", 20000), "EXP-1");
        instances.insert(expense.id(), started);

        LoadedInstance loaded = instances.load(started.id()).orElseThrow();
        InstanceState state = loaded.state();

        assertThat(loaded.version()).isZero();
        assertThat(loaded.definitionId()).isEqualTo(expense.id());
        assertThat(state.businessKey()).isEqualTo("EXP-1");
        assertThat(state.startedAt()).isEqualTo(started.startedAt());
        assertThat(state.variables()).containsEntry("initiator", "carol");
        assertThat((Map<String, Object>) state.variables().get("form")).containsEntry("amount", 20000);
        assertThat(state.tokens()).usingRecursiveFieldByFieldElementComparator().isEqualTo(started.tokens());
        assertThat(state.tasks()).usingRecursiveFieldByFieldElementComparator().isEqualTo(started.tasks());
        assertThat(state.jobs()).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrderElementsOf(started.jobs());
    }

    @Test
    void updatesAdvanceTheVersionAndStaleUpdatesAreRejected() {
        InstanceState started = engine.start(process, "carol", Map.of("amount", 20000), null);
        instances.insert(expense.id(), started);
        LoadedInstance first = instances.load(started.id()).orElseThrow();
        LoadedInstance stale = instances.load(started.id()).orElseThrow();
        Task managerTask = first.state().openTasks().getFirst();

        engine.completeTask(process, first.state(), managerTask.id(), "bob", "APPROVE", Map.of());
        instances.update(first);

        LoadedInstance reloaded = instances.load(started.id()).orElseThrow();
        assertThat(reloaded.version()).isEqualTo(1);
        assertThat(reloaded.state().openTasks()).singleElement().satisfies(t -> {
            assertThat(t.nodeId()).isEqualTo("financeApproval");
            assertThat(t.candidates()).containsExactly("role:wms-admin");
        });
        assertThat(instances.instanceOfTask(managerTask.id())).contains(started.id());
        assertThatThrownBy(() -> instances.update(stale)).isInstanceOf(OptimisticConflictException.class);
    }
}
