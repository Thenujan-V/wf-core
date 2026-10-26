package lk.auradot.wms.document;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.UUID;

/** Documents written straight into the tables, for tests that are not about uploading. */
public final class TestDocuments {

    private TestDocuments() {
    }

    /** One version per label ("1.0", "1.1", ...); the last is current. Objects are not uploaded. */
    public static String create(JdbcClient jdbc, String folderId, String name, String owner, String... labels) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        INSERT INTO document (id, folder_id, name, metadata_json, search_text, created_by, created_at, updated_at)
                        VALUES (:id, :folder, :name, '{}', :name, :owner, NOW(6), NOW(6))""")
                .param("id", id).param("folder", folderId).param("name", name).param("owner", owner).update();
        String current = null;
        for (String label : labels) {
            String[] parts = label.split("\\.");
            current = UUID.randomUUID().toString();
            jdbc.sql("""
                            INSERT INTO document_version (id, document_id, major, minor, s3_key, size, mime, sha256, file_name,
                                                          created_by, created_at)
                            VALUES (:id, :doc, :major, :minor, :key, 10, 'application/pdf', :sha, :file, :owner, NOW(6))""")
                    .param("id", current).param("doc", id).param("major", Integer.parseInt(parts[0]))
                    .param("minor", Integer.parseInt(parts[1])).param("key", "objects/" + current)
                    .param("sha", "a".repeat(64)).param("file", name).param("owner", owner).update();
        }
        jdbc.sql("UPDATE document SET current_version_id = :v WHERE id = :id").param("v", current).param("id", id).update();
        return id;
    }
}
