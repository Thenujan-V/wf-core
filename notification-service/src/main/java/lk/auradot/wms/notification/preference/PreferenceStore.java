package lk.auradot.wms.notification.preference;

import lk.auradot.wms.notification.plan.Kind;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Spec §8 "honours per-user preferences": a person's choice per kind; without one, the kind's defaults. */
@Repository
public class PreferenceStore {

    private final JdbcClient jdbc;

    public PreferenceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Preference of(String userId, Kind kind) {
        return jdbc.sql("SELECT in_app, email FROM notification_preference WHERE user_id = :user AND kind = :kind")
                .param("user", userId).param("kind", kind.name())
                .query((rs, row) -> new Preference(kind, rs.getBoolean("in_app"), rs.getBoolean("email")))
                .optional().orElseGet(() -> Preference.defaults(kind));
    }

    /** Every kind, in a fixed order, with the person's choice or its defaults. */
    public List<Preference> all(String userId) {
        Map<Kind, Preference> stored = new EnumMap<>(Kind.class);
        jdbc.sql("SELECT kind, in_app, email FROM notification_preference WHERE user_id = :user").param("user", userId)
                .query((rs, row) -> {
                    try {
                        Kind kind = Kind.valueOf(rs.getString("kind"));
                        stored.put(kind, new Preference(kind, rs.getBoolean("in_app"), rs.getBoolean("email")));
                    } catch (IllegalArgumentException e) {
                        // a kind this version no longer has
                    }
                    return null;
                }).list();
        return Arrays.stream(Kind.values()).map(kind -> stored.getOrDefault(kind, Preference.defaults(kind))).toList();
    }

    /** Replaces the person's choices; kinds that are not given go back to their defaults. */
    @Transactional
    public void replace(String userId, List<Preference> preferences) {
        jdbc.sql("DELETE FROM notification_preference WHERE user_id = :user").param("user", userId).update();
        for (Preference preference : preferences) {
            jdbc.sql("INSERT INTO notification_preference (user_id, kind, in_app, email) VALUES (:user, :kind, :inApp, :email)")
                    .param("user", userId).param("kind", preference.kind().name())
                    .param("inApp", preference.inApp()).param("email", preference.email()).update();
        }
    }
}
