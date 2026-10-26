package lk.auradot.wms.workflow.job;

import com.sun.net.httpserver.HttpServer;
import lk.auradot.wms.engine.runtime.InstanceStatus;
import lk.auradot.wms.engine.runtime.ProcessEngine;
import lk.auradot.wms.workflow.MutableClock;
import lk.auradot.wms.workflow.StubDirectory;
import lk.auradot.wms.workflow.TestDb;
import lk.auradot.wms.workflow.TestProcesses;
import lk.auradot.wms.workflow.WorkflowIntegrationTest;
import lk.auradot.wms.workflow.definition.DefinitionStore;
import lk.auradot.wms.workflow.instance.WorkflowCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@WorkflowIntegrationTest
@TestPropertySource(properties = "wms.workflow.webhooks.allow-private-networks=true")
class JobExternalActionsTest {

    @Autowired JdbcClient jdbc;
    @Autowired DefinitionStore definitions;
    @Autowired WorkflowCommands commands;
    @Autowired ProcessEngine engine;
    @Autowired JobPoller poller;
    @Autowired StubDirectory directory;
    @Autowired MutableClock clock;

    HttpServer server;
    final AtomicInteger calls = new AtomicInteger();
    volatile int status = 204;

    @BeforeEach
    void reset() throws Exception {
        TestDb.clear(jdbc);
        directory.reset();
        clock.set(MutableClock.DEFAULT);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/hook", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        definitions.saveDraft("webhook", "Webhook",
                TestProcesses.webhookTo("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"), "bob");
        definitions.publish("webhook", "bob");
        definitions.saveDraft("expense-approval", "Expense", TestProcesses.load("expense-approval.json"), "bob");
        definitions.publish("expense-approval", "bob");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void webhookIsCalledOnceThenTheProcessContinues() {
        String id = commands.start("webhook", "carol", Map.of(), null);

        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(poller.pollOnce()).isZero();

        assertThat(calls.get()).isEqualTo(1);
        String node = commands.read(id, (p, s) -> s.openTasks().getFirst().nodeId());
        assertThat(node).isEqualTo("review");
    }

    @Test
    void failingWebhookIsRetriedWithBackoff() {
        status = 503;
        String id = commands.start("webhook", "carol", Map.of(), null);

        poller.pollOnce();

        assertThat(jdbc.sql("SELECT CONCAT(status, '/', attempts, '/', last_error) FROM job WHERE instance_id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("PENDING/1/Webhook returned HTTP 503");
        assertThat(poller.pollOnce()).as("backing off").isZero();
    }

    @Test
    void emailActionsCompleteAndRequestTheEmail() {
        String id = commands.start("expense-approval", "carol", Map.of("amount", 500), null);
        String managerTask = commands.read(id, (p, s) -> s.openTasks().getFirst().id());
        commands.onTask(managerTask, "bob", (p, s) -> engine.completeTask(p, s, managerTask, "bob", "APPROVE", Map.of()));

        assertThat(poller.pollOnce()).isEqualTo(1);

        InstanceStatus status = commands.read(id, (p, s) -> s.status());
        assertThat(status).isEqualTo(InstanceStatus.COMPLETED);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM outbox WHERE routing_key = 'workflow.email.requested'")
                .query(Integer.class).single()).isEqualTo(1);
    }
}
