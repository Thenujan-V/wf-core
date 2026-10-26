package lk.auradot.wms.document;

import lk.auradot.wms.document.folder.Folders;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Empties every table except the seeded root folder and its ACL. */
public final class TestDb {

    private TestDb() {
    }

    public static void clear(JdbcClient jdbc) {
        for (String table : new String[] {"processed_message", "outbox", "upload", "document_text", "document_version"}) {
            jdbc.sql("DELETE FROM " + table).update();
        }
        jdbc.sql("UPDATE document SET current_version_id = NULL").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM document_type").update();
        jdbc.sql("DELETE FROM folder_acl WHERE folder_id <> :root").param("root", Folders.ROOT_ID).update();
        // Children before parents: repeat until only the seeded system folders are left.
        while (jdbc.sql("""
                        DELETE FROM folder WHERE id NOT IN (:root, :processes, :staging) AND id NOT IN (
                            SELECT parent_id FROM (SELECT parent_id FROM folder WHERE parent_id IS NOT NULL) AS p)""")
                .param("root", Folders.ROOT_ID).param("processes", Folders.PROCESSES_ID).param("staging", Folders.STAGING_ID).update() > 0) {
            // keep going
        }
        jdbc.sql("DELETE FROM folder_acl WHERE folder_id = :root").param("root", Folders.ROOT_ID).update();
        jdbc.sql("""
                        INSERT INTO folder_acl (folder_id, principal_type, principal_id, permission) VALUES
                        (:root, 'ROLE', 'user', 'READ'), (:root, 'ROLE', 'document-admin', 'MANAGE')""")
                .param("root", Folders.ROOT_ID).update();
    }
}
