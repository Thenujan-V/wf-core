package lk.auradot.wms.document.folder;

import lk.auradot.wms.document.Times;
import lk.auradot.wms.document.access.AclEntry;
import lk.auradot.wms.document.access.FolderNode;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.access.PrincipalType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class FolderStore {

    private final JdbcClient jdbc;

    public FolderStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<FolderNode> loadNodes() {
        return jdbc.sql("SELECT id, parent_id, name, inherit_acl FROM folder")
                .query((rs, n) -> new FolderNode(rs.getString("id"), rs.getString("parent_id"), rs.getString("name"),
                        rs.getBoolean("inherit_acl")))
                .list();
    }

    public Map<String, List<AclEntry>> loadEntries() {
        Map<String, List<AclEntry>> entries = new HashMap<>();
        jdbc.sql("SELECT folder_id, principal_type, principal_id, permission FROM folder_acl ORDER BY principal_type, principal_id")
                .query(rs -> {
                    entries.computeIfAbsent(rs.getString("folder_id"), k -> new ArrayList<>()).add(new AclEntry(
                            PrincipalType.valueOf(rs.getString("principal_type")), rs.getString("principal_id"),
                            Permission.valueOf(rs.getString("permission"))));
                });
        return entries;
    }

    public void insert(String id, String parentId, String name, String userId, Instant now) {
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :parent, :name, TRUE, :user, :at)""")
                .param("id", id).param("parent", parentId).param("name", name).param("user", userId)
                .param("at", Times.toDb(now)).update();
    }

    public void rename(String id, String name) {
        jdbc.sql("UPDATE folder SET name = :name WHERE id = :id").param("name", name).param("id", id).update();
    }

    public void delete(String id) {
        jdbc.sql("DELETE FROM folder WHERE id = :id").param("id", id).update();
    }

    public boolean hasContent(String id) {
        return jdbc.sql("""
                        SELECT (SELECT COUNT(*) FROM folder WHERE parent_id = :id) + (SELECT COUNT(*) FROM document WHERE folder_id = :id)""")
                .param("id", id).query(Long.class).single() > 0;
    }

    public void replaceAcl(String id, boolean inheritAcl, List<AclEntry> entries) {
        jdbc.sql("UPDATE folder SET inherit_acl = :inherit WHERE id = :id").param("inherit", inheritAcl).param("id", id).update();
        jdbc.sql("DELETE FROM folder_acl WHERE folder_id = :id").param("id", id).update();
        for (AclEntry e : entries) {
            jdbc.sql("""
                            INSERT INTO folder_acl (folder_id, principal_type, principal_id, permission)
                            VALUES (:id, :type, :principal, :permission)""")
                    .param("id", id).param("type", e.principalType().name()).param("principal", e.principalId())
                    .param("permission", e.permission().name()).update();
        }
    }

    public java.util.Optional<String> parentOf(String id) {
        return jdbc.sql("SELECT parent_id FROM folder WHERE id = :id AND parent_id IS NOT NULL").param("id", id)
                .query(String.class).optional();
    }

    public java.util.Optional<String> findChild(String parentId, String name) {
        return jdbc.sql("SELECT id FROM folder WHERE parent_id = :parent AND name = :name")
                .param("parent", parentId).param("name", name).query(String.class).optional();
    }

    /** A folder of the system (no ACL, no inheritance): only admins and the request's own access reach it. */
    public void insertSystem(String id, String parentId, String name, Instant now) {
        jdbc.sql("""
                        INSERT INTO folder (id, parent_id, name, inherit_acl, created_by, created_at)
                        VALUES (:id, :parent, :name, FALSE, 'system', :at)""")
                .param("id", id).param("parent", parentId).param("name", name).param("at", Times.toDb(now)).update();
    }

    /** Staged-upload folders created before the cutoff whose documents (if any) are older too. */
    public List<String> stagedBefore(Instant cutoff) {
        return jdbc.sql("""
                        SELECT f.id FROM folder f
                        WHERE f.parent_id = :staging AND f.created_at < :cutoff
                          AND NOT EXISTS (SELECT 1 FROM document d WHERE d.folder_id = f.id AND d.created_at >= :cutoff)""")
                .param("staging", Folders.STAGING_ID).param("cutoff", Times.toDb(cutoff)).query(String.class).list();
    }
}
