package lk.auradot.wms.identity.sync;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import lk.auradot.wms.identity.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IdentityIntegrationTest
class FullSyncServiceTest {

    @Autowired
    JdbcClient jdbc;
    @Autowired
    FullSyncService sync;
    @Autowired
    SyncLease lease;
    @Autowired
    MockMvc mvc;
    @MockitoBean
    DirectorySnapshotLoader loader;
    TestData data;

    private static final DirectorySnapshot ONE_USER = new DirectorySnapshot(
            List.of(new KcUser("u-admin", "alice", "alice@example.com", "Alice", "Admin", true, Map.of())),
            Map.of("u-admin", List.of("wms-admin")), List.of(), Map.of());

    @BeforeEach
    void reset() {
        data = new TestData(jdbc);
        data.clear();
    }

    @Test
    void runOnceAppliesSnapshot() {
        when(loader.load()).thenReturn(ONE_USER);

        assertThat(sync.runOnce()).hasValueSatisfying(r -> assertThat(r.users()).isEqualTo(1));
        assertThat(jdbc.sql("SELECT username FROM app_user").query(String.class).list()).containsExactly("alice");
    }

    @Test
    void skipsWhenAnotherReplicaHoldsTheLease() {
        lease.tryAcquire(FullSyncService.LEASE, "other-replica", Instant.now(), Duration.ofMinutes(10));

        assertThat(sync.runOnce()).isEmpty();
        verify(loader, never()).load();
    }

    @Test
    void refusesSecondSyncInSameProcessWhileOneIsRunning() {
        // The scheduler and POST /sync share one FullSyncService; a sync started mid-run must be refused.
        when(loader.load()).thenAnswer(invocation -> {
            assertThat(sync.runOnce()).as("overlapping sync").isEmpty();
            return ONE_USER;
        });

        assertThat(sync.runOnce()).isPresent();
    }

    @Test
    void releasesLeaseWhenKeycloakFails() {
        when(loader.load()).thenThrow(new ResourceAccessException("connection refused"));

        assertThatThrownBy(() -> sync.runOnce()).isInstanceOf(ResourceAccessException.class);

        assertThat(lease.tryAcquire(FullSyncService.LEASE, "next", Instant.now(), Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void adminCanTriggerSync() throws Exception {
        when(loader.load()).thenReturn(ONE_USER);
        data.user("u-admin", "alice", "Alice", "Admin");

        mvc.perform(post("/sync").with(TestJwt.user("u-admin", "alice", "wms-admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").value(1));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void logLinesCarryTheRequestCorrelationId(CapturedOutput output) throws Exception {
        when(loader.load()).thenReturn(ONE_USER);
        data.user("u-admin", "alice", "Alice", "Admin");

        mvc.perform(post("/sync").header("X-Correlation-Id", "corr-log-12345")
                        .with(TestJwt.user("u-admin", "alice", "wms-admin")))
                .andExpect(status().isOk());

        assertThat(output.getOut().lines().filter(line -> line.contains("Directory sync applied")))
                .anySatisfy(line -> assertThat(line).contains("corr-log-12345"));
    }

    @Test
    void nonAdminCannotTriggerSync() throws Exception {
        mvc.perform(post("/sync").with(TestJwt.user("u-carol", "carol", "user")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsKeycloakOutageAsBadGateway() throws Exception {
        when(loader.load()).thenThrow(new ResourceAccessException("connection refused"));
        data.user("u-admin", "alice", "Alice", "Admin");

        mvc.perform(post("/sync").with(TestJwt.user("u-admin", "alice", "wms-admin")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("IDN_KEYCLOAK_UNAVAILABLE"));
    }
}
