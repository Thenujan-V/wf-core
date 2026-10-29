package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.runtime.InstanceStatus;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.engine.runtime.Task;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WorkflowIntegrationTest
class WorkflowCommandsTest {

    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired WorkflowCommands commands;
    @Autowired ProcessEngine engine;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        definitions.saveDraft("expense-approval", "Expense approval", TestProcesses.load("expense-approval.json"), "bob");
        definitions.publish("expense-approval", "bob");
        definitions.saveDraft("parallel-signoff", "Parallel sign-off", TestProcesses.load("parallel-signoff.json"), "bob");
        definitions.publish("parallel-signoff", "bob");
    }

    private List<Task> openTasks(String instanceId) {
        return commands.read(instanceId, (p, s) -> s.openTasks());
    }

    private void complete(String taskId, String user, String outcome) {
        commands.onTask(taskId, user, (p, s) -> engine.completeTask(p, s, taskId, user, outcome, Map.of()));
    }

    @Test
    void startsAndAdvancesAnInstanceAcrossTransactions() {
        String id = commands.start("expense-approval", "carol", Map.of("amount", 500), "EXP-9");
        Task manager = openTasks(id).getFirst();
        assertThat(manager.assigneeId()).isEqualTo("bob");

        complete(manager.id(), "bob", "APPROVE");

        assertThat(openTasks(id)).isEmpty();
        InstanceStatus status = commands.read(id, (p, s) -> s.status());
        assertThat(status).isEqualTo(InstanceStatus.ACTIVE);
        assertThat(jdbc.sql("SELECT version FROM process_instance WHERE id = :id").param("id", id)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM history_event WHERE instance_id = :id AND actor_id = 'bob'")
                .param("id", id).query(Integer.class).single()).isPositive();
    }

    @Test
    void engineRefusalsBecomeApiErrorsAndChangeNothing() {
        String id = commands.start("expense-approval", "carol", Map.of(), null);
        Task manager = openTasks(id).getFirst();

        assertThatThrownBy(() -> complete(manager.id(), "dave", "APPROVE"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("WF_NOT_ASSIGNEE");
                    assertThat(e.status().value()).isEqualTo(403);
                });
        assertThat(openTasks(id)).extracting(Task::id).containsExactly(manager.id());
        assertThatThrownBy(() -> complete("no-such-task", "bob", "APPROVE"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WF_TASK_NOT_FOUND"));
        assertThatThrownBy(() -> commands.start("unknown", "carol", Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("WF_PROCESS_NOT_FOUND"));
    }

    @Test
    void concurrentCompletionsOfParallelTasksBothSucceedAndJoinOnce() throws Exception {
        String id = commands.start("parallel-signoff", "carol", Map.of(), null);
        List<Task> branches = openTasks(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (Task task : branches) {
            results.add(pool.submit(() -> {
                go.await();
                complete(task.id(), task.assigneeId(), "OK");
                return null;
            }));
        }
        go.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();

        assertThat(openTasks(id)).singleElement().satisfies(t -> assertThat(t.nodeId()).isEqualTo("signOff"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM task WHERE instance_id = :id AND node_id = 'signOff'")
                .param("id", id).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void directoryOutageFailsTheCommandAndPersistsNothing() {
        directory.failing(true);

        assertThatThrownBy(() -> commands.start("expense-approval", "carol", Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("WF_DIRECTORY_UNAVAILABLE");
                    assertThat(e.status().value()).isEqualTo(503);
                });
        assertThat(jdbc.sql("SELECT COUNT(*) FROM process_instance").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox").query(Integer.class).single()).isZero();
    }
}
