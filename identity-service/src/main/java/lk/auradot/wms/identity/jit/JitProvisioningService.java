package lk.auradot.wms.identity.jit;

import lk.auradot.wms.common.security.KeycloakRealmRoleConverter;
import lk.auradot.wms.identity.user.Times;
import lk.auradot.wms.identity.user.UserRecord;
import lk.auradot.wms.identity.user.UserWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

/** Keeps the local user copy current from the caller's token (spec §7: JIT when unknown or older than 1h). */
@Service
public class JitProvisioningService {

    static final Duration STALE_AFTER = Duration.ofHours(1);

    private final JdbcClient jdbc;
    private final UserWriter writer;
    private final Clock clock;

    public JitProvisioningService(JdbcClient jdbc, UserWriter writer, Clock clock) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.clock = clock;
    }

    static final String SERVICE_ACCOUNT_PREFIX = "service-account-";

    @Transactional
    public void ensureFresh(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        if (username != null && username.startsWith(SERVICE_ACCOUNT_PREFIX)) {
            return; // services calling other services are not people and must not appear in the directory
        }
        Instant now = clock.instant();
        Optional<LocalDateTime> syncedAt = jdbc.sql("SELECT synced_at FROM app_user WHERE id = :id")
                .param("id", jwt.getSubject())
                .query(LocalDateTime.class)
                .optional();
        if (syncedAt.isPresent() && Times.fromDb(syncedAt.get()).isAfter(now.minus(STALE_AFTER))) {
            return;
        }
        writer.upsertCore(new UserRecord(
                jwt.getSubject(),
                username != null ? username : jwt.getSubject(),
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("given_name"),
                jwt.getClaimAsString("family_name"),
                true), now);
        writer.replaceRoles(jwt.getSubject(), KeycloakRealmRoleConverter.realmRoles(jwt));
    }
}
