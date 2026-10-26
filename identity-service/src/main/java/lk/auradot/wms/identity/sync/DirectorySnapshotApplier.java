package lk.auradot.wms.identity.sync;

import lk.auradot.wms.identity.org.OrgField;
import lk.auradot.wms.identity.org.OrgSource;
import lk.auradot.wms.identity.org.OrgSourceProperties;
import lk.auradot.wms.identity.user.UserRecord;
import lk.auradot.wms.identity.user.UserWriter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DirectorySnapshotApplier {

    private final JdbcClient jdbc;
    private final UserWriter writer;
    private final OrgSourceProperties orgSource;

    public DirectorySnapshotApplier(JdbcClient jdbc, UserWriter writer, OrgSourceProperties orgSource) {
        this.jdbc = jdbc;
        this.writer = writer;
        this.orgSource = orgSource;
    }

    @Transactional
    public SyncResult apply(DirectorySnapshot snapshot, Instant now) {
        if (snapshot.users().isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to apply a directory snapshot with no users; check the sync client's Keycloak permissions");
        }
        Set<String> userIds = new LinkedHashSet<>();
        for (KcUser user : snapshot.users()) {
            userIds.add(user.id());
            writer.upsertCore(new UserRecord(user.id(), user.username(), user.email(), user.firstName(),
                    user.lastName(), !Boolean.FALSE.equals(user.enabled())), now);
            writer.replaceRoles(user.id(), snapshot.rolesByUserId().getOrDefault(user.id(), List.of()));
            if (orgSource.sourceOf(OrgField.DEPARTMENT) == OrgSource.KEYCLOAK) {
                writer.updateOrg(user.id(), OrgField.DEPARTMENT, user.attribute("department"));
            }
            if (orgSource.sourceOf(OrgField.POSITION) == OrgSource.KEYCLOAK) {
                writer.updateOrg(user.id(), OrgField.POSITION, user.attribute("position"));
            }
        }
        if (orgSource.sourceOf(OrgField.MANAGER) == OrgSource.KEYCLOAK) {
            applyManagers(snapshot.users());
        }
        int disabled = jdbc.sql("UPDATE app_user SET enabled = FALSE WHERE enabled = TRUE AND id NOT IN (:ids)")
                .param("ids", userIds)
                .update();
        applyGroups(snapshot, userIds);
        return new SyncResult(userIds.size(), snapshot.groups().size(), disabled);
    }

    /** The {@code manager} attribute holds the manager's username. Unknown or self references are cleared. */
    private void applyManagers(List<KcUser> users) {
        Map<String, String> idByUsername = users.stream().collect(Collectors.toMap(
                u -> u.username().toLowerCase(Locale.ROOT), KcUser::id, (first, second) -> first));
        for (KcUser user : users) {
            String managerUsername = user.attribute("manager");
            String managerId = managerUsername == null ? null : idByUsername.get(managerUsername.toLowerCase(Locale.ROOT));
            if (user.id().equals(managerId)) {
                managerId = null;
            }
            writer.updateOrg(user.id(), OrgField.MANAGER, managerId);
        }
    }

    private void applyGroups(DirectorySnapshot snapshot, Set<String> userIds) {
        Set<String> groupIds = new LinkedHashSet<>();
        for (KcGroup group : snapshot.groups()) {
            groupIds.add(group.id());
            jdbc.sql("""
                            INSERT INTO app_group (id, name, path) VALUES (:id, :name, :path) AS new
                            ON DUPLICATE KEY UPDATE name = new.name, path = new.path""")
                    .param("id", group.id()).param("name", group.name()).param("path", group.path())
                    .update();
        }
        if (groupIds.isEmpty()) {
            jdbc.sql("DELETE FROM app_group").update();
        } else {
            jdbc.sql("DELETE FROM app_group WHERE id NOT IN (:ids)").param("ids", groupIds).update();
        }
        jdbc.sql("DELETE FROM user_group").update();
        snapshot.memberIdsByGroupId().forEach((groupId, memberIds) -> {
            if (!groupIds.contains(groupId)) {
                return;
            }
            for (String memberId : new LinkedHashSet<>(memberIds)) {
                if (userIds.contains(memberId)) {
                    jdbc.sql("INSERT INTO user_group (user_id, group_id) VALUES (:userId, :groupId)")
                            .param("userId", memberId).param("groupId", groupId)
                            .update();
                }
            }
        });
    }
}
