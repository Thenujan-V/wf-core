package lk.auradot.wms.document.text;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TextExtractorTest {

    final TextExtractor extractor = new TextExtractor();

    @TempDir
    Path dir;

    Path file(String name, byte[] content) throws IOException {
        return Files.write(dir.resolve(name), content);
    }

    static byte[] pdf(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void readsPlainTextAndPdf() throws IOException {
        assertThat(extractor.extract(file("notes.txt", "quarterly budget review".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("quarterly budget review");
        assertThat(extractor.extract(file("plan.pdf", pdf("Strategic plan for the harbour project"))))
                .contains("Strategic plan for the harbour project");
    }

    @Test
    void damagedOrBinaryFilesGiveNoTextInsteadOfFailing() throws IOException {
        byte[] noise = new byte[4096];
        new Random(7).nextBytes(noise);

        assertThat(extractor.extract(file("broken.pdf", "%PDF-1.4 this is not really a pdf".getBytes(StandardCharsets.US_ASCII))))
                .isEmpty();
        assertThat(extractor.extract(file("noise.bin", noise))).isEmpty();
        assertThat(extractor.extract(dir.resolve("missing.txt"))).isEmpty();
    }

    @Test
    void keepsAtMostAMillionCharacters() throws IOException {
        String big = "word ".repeat(300_000);

        String text = extractor.extract(file("big.txt", big.getBytes(StandardCharsets.UTF_8)));

        assertThat(text.length()).isLessThanOrEqualTo(TextExtractor.MAX_CHARS).isGreaterThan(900_000);
    }

    @Test
    void aFileThatKeepsTheParserBusyTooLongGivesNoText() throws IOException {
        TextExtractor slow = new TextExtractor(Duration.ofMillis(200), path -> {
            Thread.sleep(10_000);
            return "never";
        });
        long started = System.nanoTime();

        assertThat(slow.extract(file("slow.txt", new byte[] {1}))).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aParserThatRunsOutOfStackGivesNoTextAndLeavesTheServiceRunning() throws IOException {
        TextExtractor broken = new TextExtractor(Duration.ofSeconds(5), path -> {
            throw new StackOverflowError("pathological nesting");
        });

        assertThat(broken.extract(file("deep.xml", new byte[] {1}))).isEmpty();
        assertThat(broken.extract(file("deep2.xml", new byte[] {1}))).isEmpty();
    }
}
