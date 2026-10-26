package lk.auradot.wms.document.text;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.TestDocuments;
import lk.auradot.wms.document.TestFiles;
import lk.auradot.wms.document.folder.Folders;
import lk.auradot.wms.document.storage.ObjectStore;
import lk.auradot.wms.document.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** A version whose extraction message was lost (broker or storage trouble) still gets its text later. */
@DocumentIntegrationTest
class TextBackfillTest {

    @Autowired JdbcClient jdbc;
    @Autowired ObjectStore objects;
    @Autowired TextBackfill backfill;
    @Autowired MutableClock clock;

    /** Test documents are stamped with the database's real time, so the backfill's clock must be past it. */
    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        clock.set(Instant.now().plus(Duration.ofHours(1)));
    }

    @AfterEach
    void restoreClock() {
        clock.set(MutableClock.DEFAULT);
    }

    String currentVersion(String documentId) {
        return jdbc.sql("SELECT current_version_id FROM document WHERE id = :id").param("id", documentId).query(String.class).single();
    }

    Optional<String> text(String versionId) {
        return jdbc.sql("SELECT content FROM document_text WHERE version_id = :v").param("v", versionId).query(String.class).optional();
    }

    @Test
    void extractsCurrentVersionsThatHaveNoTextYet() {
        String notes = TestDocuments.create(jdbc, Folders.ROOT_ID, "Notes.txt", "bob", "1.0");
        String version = currentVersion(notes);
        byte[] body = "minutes of the harbour board".getBytes(StandardCharsets.UTF_8);
        TestFiles.put(objects.presignUpload("objects/" + version, body.length, "text/plain", Duration.ofMinutes(5)), body, "text/plain");
        String gone = currentVersion(TestDocuments.create(jdbc, Folders.ROOT_ID, "Gone.txt", "bob", "1.0"));

        assertThat(backfill.run()).isEqualTo(2);

        assertThat(text(version)).contains("minutes of the harbour board");
        // A version whose file is gone gets empty text, so it is not tried again and again.
        assertThat(text(gone)).contains("");
        assertThat(backfill.run()).isZero();
    }
}
