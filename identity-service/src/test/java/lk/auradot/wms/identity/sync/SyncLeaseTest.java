package lk.auradot.wms.identity.sync;

import lk.auradot.wms.identity.IdentityIntegrationTest;
import lk.auradot.wms.identity.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@IdentityIntegrationTest
class SyncLeaseTest {

    private static final String NAME = "identity-full-sync";
    private static final Instant T0 = Instant.parse("2026-09-24T10:00:00Z");

    @Autowired
    JdbcClient jdbc;
    @Autowired
    SyncLease lease;

    @BeforeEach
    void reset() {
        new TestData(jdbc).clear();
    }

    @Test
    void onlyOneOwnerHoldsTheLease() {
        assertThat(lease.tryAcquire(NAME, "a", T0, Duration.ofMinutes(10))).isTrue();
        assertThat(lease.tryAcquire(NAME, "b", T0.plusSeconds(60), Duration.ofMinutes(10))).isFalse();
        assertThat(lease.tryAcquire(NAME, "a", T0.plusSeconds(60), Duration.ofMinutes(10)))
                .as("not re-entrant: a held lease blocks everyone until released or expired").isFalse();
    }

    @Test
    void expiredLeaseCanBeTakenOver() {
        lease.tryAcquire(NAME, "a", T0, Duration.ofMinutes(10));

        assertThat(lease.tryAcquire(NAME, "b", T0.plus(Duration.ofMinutes(11)), Duration.ofMinutes(10))).isTrue();
    }

    @Test
    void releaseLetsOthersAcquire() {
        lease.tryAcquire(NAME, "a", T0, Duration.ofMinutes(10));
        lease.release(NAME, "a");

        assertThat(lease.tryAcquire(NAME, "b", T0.plusSeconds(6), Duration.ofMinutes(10))).isTrue();
    }
}
