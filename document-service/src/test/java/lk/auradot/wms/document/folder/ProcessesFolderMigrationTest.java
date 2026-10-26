package lk.auradot.wms.document.folder;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.access.AclSnapshots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V2 seeds /Processes; a library that already has a root folder of that name keeps it, renamed. */
@DocumentIntegrationTest
class ProcessesFolderMigrationTest {

    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;

    @BeforeEach
    @AfterEach
    void reset() {
        TestDb.clear(jdbc);
        snapshots.invalidate();
    }

    @Test
    void anExistingRootFolderNamedProcessesIsKeptUnderAnotherName() throws Exception {
        // /Processes/Staged uploads (V3) hangs below it: take both away, then replay V2 and V3 on this library.
        jdbc.sql("DELETE FROM folder WHERE id = :id").param("id", Folders.STAGING_ID).update();
        jdbc.sql("DELETE FROM folder WHERE id = :id").param("id", Folders.PROCESSES_ID).update();
        String mine = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO folder VALUES (:id, :root, 'processes', TRUE, 'dave', NOW(6))")
                .param("id", mine).param("root", Folders.ROOT_ID).update();

        replay("db/migration/V2__request_documents.sql");
        replay("db/migration/V3__staged_uploads.sql");

        assertThat(jdbc.sql("SELECT name FROM folder WHERE id = :id").param("id", mine).query(String.class).single())
                .isEqualTo("processes (library)");
        assertThat(jdbc.sql("SELECT name FROM folder WHERE id = :id").param("id", Folders.PROCESSES_ID).query(String.class).single())
                .isEqualTo("Processes");
        assertThat(jdbc.sql("SELECT parent_id FROM folder WHERE id = :id").param("id", Folders.STAGING_ID).query(String.class).single())
                .isEqualTo(Folders.PROCESSES_ID);
    }

    /** Runs a migration's data statements again (schema changes are already applied). */
    private void replay(String migration) throws Exception {
        String script = new ClassPathResource(migration).getContentAsString(StandardCharsets.UTF_8);
        String withoutComments = String.join("\n", script.lines().filter(line -> !line.strip().startsWith("--")).toList());
        Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty() && !statement.toUpperCase().startsWith("ALTER TABLE"))
                .forEach(statement -> jdbc.sql(statement).update());
    }
}
