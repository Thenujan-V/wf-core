package lk.auradot.wms.document.text;

import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Plain text of a file for search (spec §6: Apache Tika, up to about 1 MB of text). */
@Component
public class TextExtractor {

    public static final int MAX_CHARS = 1_000_000;
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(TextExtractor.class);

    /** Reads a file's text; any failure is about the file's content. */
    interface Parser {
        String parse(Path file) throws Exception;
    }

    private final Duration timeout;
    private final Parser parser;
    /** Parses run on their own threads, so a file that hangs a parser cannot stop extraction for everyone else. */
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "text-extraction-worker");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public TextExtractor() {
        this(DEFAULT_TIMEOUT, null);
    }

    TextExtractor(Duration timeout, Parser parser) {
        this.timeout = timeout;
        this.parser = parser != null ? parser : tikaParser();
    }

    private static Parser tikaParser() {
        Tika tika = new Tika();
        tika.setMaxStringLength(MAX_CHARS);
        return file -> {
            try (InputStream in = Files.newInputStream(file)) {
                return tika.parseToString(in);
            }
        };
    }

    /**
     * Never fails: a file that cannot be parsed (damaged, encrypted, unknown format, too slow, or one that exhausts
     * the parser's stack or memory) simply has no text. The file is local, so retrying would not help.
     */
    public String extract(Path file) {
        Future<String> job = workers.submit(() -> parser.parse(file));
        try {
            String text = job.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            text = text == null ? "" : text.strip();
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (TimeoutException e) {
            job.cancel(true);
            log.warn("Gave up extracting text from {} after {}", file.getFileName(), timeout);
            return "";
        } catch (ExecutionException e) {
            log.info("No text extracted from {}: {}", file.getFileName(), e.getCause().toString());
            return "";
        } catch (InterruptedException e) {
            job.cancel(true);
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
