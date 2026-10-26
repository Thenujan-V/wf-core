package lk.auradot.wms.document;

import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DocumentIntegrationTest
class SchemaMigrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void createsDocumentTables() {
        var tables = jdbc.sql("""
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
                        ORDER BY table_name""")
                .query(String.class).list();

        assertThat(tables).containsExactly("document", "document_draft", "document_submission", "document_text", "document_type",
                "document_version", "folder", "folder_acl", "outbox", "processed_message", "upload");
    }

    @Test
    void documentsAreFilesUnlessSaidOtherwise() {
        String defaultKind = jdbc.sql("""
                        SELECT column_default FROM information_schema.columns
                        WHERE table_schema = DATABASE() AND table_name = 'document' AND column_name = 'kind'""")
                .query(String.class).single();

        assertThat(defaultKind).isEqualTo("FILE");
    }

    @Test
    void seedsTheLibraryRootReadableByEveryUserAndManagedByDocumentAdmins() {
        String name = jdbc.sql("SELECT name FROM folder WHERE id = :id AND parent_id IS NULL")
                .param("id", Folders.ROOT_ID).query(String.class).single();
        List<Map<String, Object>> acl = jdbc.sql("""
                        SELECT principal_type, principal_id, permission FROM folder_acl WHERE folder_id = :id
                        ORDER BY principal_id""")
                .param("id", Folders.ROOT_ID).query().listOfRows();

        assertThat(name).isEqualTo("Library");
        assertThat(acl).containsExactly(
                Map.of("principal_type", "ROLE", "principal_id", "document-admin", "permission", "MANAGE"),
                Map.of("principal_type", "ROLE", "principal_id", "user", "permission", "READ"));
    }

    @Test
    void searchIndexesUseTheNgramParser() {
        var indexes = jdbc.sql("""
                        SELECT table_name FROM information_schema.statistics
                        WHERE table_schema = DATABASE() AND index_type = 'FULLTEXT' ORDER BY table_name""")
                .query(String.class).list();

        assertThat(indexes).containsExactly("document", "document_text");
    }
}
