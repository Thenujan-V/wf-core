package lk.auradot.wms.document.text;

import lk.auradot.wms.document.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;

/**
 * Extracts the text of current versions that have none some minutes after they were created: the safety net for a
 * {@code document.version.uploaded} message that was dropped after its retries (broker, storage or database trouble).
 */
@Component
public class TextBackfill {

    /** Leaves recent versions to the event-driven listener. */
    static final Duration GRACE = Duration.ofMinutes(10);
    static final int BATCH = 20;

    private static final Logger log = LoggerFactory.getLogger(TextBackfill.class);

    private final DocumentTextStore texts;
    private final ObjectStore objects;
    private final TextExtractor extractor;
    private final Clock clock;

    public TextBackfill(DocumentTextStore texts, ObjectStore objects, TextExtractor extractor, Clock clock) {
        this.texts = texts;
        this.objects = objects;
        this.extractor = extractor;
        this.clock = clock;
    }

    /** Returns how many versions got their text in this round. */
    public int run() {
        int done = 0;
        for (DocumentTextStore.Pending pending : texts.missingCurrent(clock.instant().minus(GRACE), BATCH)) {
            String text;
            try {
                text = extract(pending.s3Key());
            } catch (ObjectStore.MissingObjectException e) {
                text = ""; // nothing to read; recorded so the version is not tried again
            } catch (IOException | RuntimeException e) {
                log.warn("Text of version {} is still missing; will retry: {}", pending.versionId(), e.getMessage());
                continue;
            }
            texts.saveIfVersionExists(pending.versionId(), pending.documentId(), text, clock.instant());
            done++;
        }
        return done;
    }

    private String extract(String key) throws IOException {
        Path file = Files.createTempFile("wms-text-", ".bin");
        try {
            try (InputStream in = objects.open(key)) {
                Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return extractor.extract(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
