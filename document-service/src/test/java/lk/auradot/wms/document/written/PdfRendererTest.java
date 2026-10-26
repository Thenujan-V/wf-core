package lk.auradot.wms.document.written;

import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PdfRendererTest {

    private final PdfRenderer renderer = new PdfRenderer();

    private static String textOf(byte[] pdf) throws Exception {
        return new Tika().parseToString(new ByteArrayInputStream(pdf));
    }

    @Test
    void makesAPdfOfTheTextWithTheNoteOnThePage() throws Exception {
        byte[] pdf = renderer.render("Travel policy", "<h1>Policy</h1><p>Fly <strong>economy</strong>.</p>"
                + "<ul><li>Book early</li></ul><table><tbody><tr><th>Class</th></tr><tr><td>Economy</td></tr></tbody></table>",
                "For review — not approved");

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = textOf(pdf);
        // Tika reads the bold word as its own run, so the sentence is checked word by word.
        assertThat(text).contains("Policy").contains("Fly").contains("economy").contains("Book early").contains("Class").contains("Economy");
        assertThat(text).contains("For review — not approved").contains("page 1 of 1");
    }

    @Test
    void aLongTextRunsOverSeveralPagesEachWithItsNumber() throws Exception {
        String text = textOf(renderer.render("Long", "<p>Paragraph of some length to fill the page.</p>".repeat(400), "Note"));

        assertThat(text).contains("page 1 of").contains("page 2 of");
    }

    @Test
    void awkwardTextStillBecomesAPdf() throws Exception {
        String html = "<p>Tom &amp; Jerry &lt;cat&gt; “quoted” — dash</p><p>Open <strong>bold<table></table>"
                + "<p>" + "W".repeat(500) + "</p><p>සිංහල 日本語</p><p></p>";

        byte[] pdf = renderer.render("Odd \"title\" \\ with quotes", html, "A \"quoted\" note \\ with a backslash");

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(textOf(pdf)).contains("Tom & Jerry <cat>").contains("Open bold");
    }

    /**
     * The renderer reads a cell's span attribute as part of its style, so a text stored before spans were checked must
     * not be able to restyle or hide what approvers read, nor ask for a billion rows.
     */
    @Test
    void aSpanAttributeCannotStyleOrHideACellOrAskForEndlessRows() throws Exception {
        String html = "<table><tbody><tr><td rowspan=\"1;display:none\">Hidden clause</td>"
                + "<td colspan=\"1;color:#ffffff\">White clause</td><td rowspan=\"999999999\">Tall clause</td></tr></tbody></table>";

        String text = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(30),
                () -> textOf(renderer.render("Spans", html, "Note")));

        assertThat(text).contains("Hidden clause").contains("White clause").contains("Tall clause");
    }

    @Test
    void anEmptyTextIsABlankPageNotAnError() throws Exception {
        assertThat(textOf(renderer.render("Empty", "", "Note"))).contains("page 1 of 1");
    }

    private static ApprovalRecord record(String documentName, String stepName, String person) {
        return new ApprovalRecord(documentName, "1.0", "DOC-42", "Document approval", "Name of bob",
                java.time.Instant.parse("2026-10-06T08:15:00Z"),
                java.util.List.of(new ApprovalRecord.Step(stepName, person, "APPROVE", java.time.Instant.parse("2026-10-06T09:30:00Z")),
                        new ApprovalRecord.Step("Finance approval", "Name of alice", "APPROVE", java.time.Instant.parse("2026-10-07T11:05:00Z"))),
                java.time.Instant.parse("2026-10-07T11:05:00Z"));
    }

    @Test
    void theApprovedPdfEndsWithAPageSayingWhoApprovedItAndWhen() throws Exception {
        String text = textOf(renderer.render("Travel policy", "<p>Fly economy.</p>", "Travel policy · version 1.0",
                record("Travel policy", "Manager approval", "Name of carol")));

        assertThat(text).contains("Fly economy.").contains("Travel policy · version 1.0").contains("page 2 of 2");
        assertThat(text).contains("Approval record").contains("Travel policy").contains("1.0").contains("DOC-42")
                .contains("Document approval").contains("Name of bob").contains("6 Oct 2026, 08:15 UTC");
        assertThat(text).contains("Manager approval").contains("Name of carol").contains("APPROVE").contains("6 Oct 2026, 09:30 UTC");
        assertThat(text).contains("Finance approval").contains("Name of alice").contains("7 Oct 2026, 11:05 UTC");
        // The record comes after the text, on its own page.
        assertThat(text.indexOf("Approval record")).isGreaterThan(text.indexOf("Fly economy."));
    }

    @Test
    void namesInTheRecordArePrintedAsTextWhateverTheyContain() throws Exception {
        String text = textOf(renderer.render("T", "<p>Body</p>", "Note",
                record("Fees & <charges> \"2026\"", "<script>alert(1)</script>", "O'Brien & Sons <b>bold</b>")));

        // A long word wraps inside its table cell, so the words are compared with the line breaks taken out.
        assertThat(text.replaceAll("\\s+", "")).contains("Fees&<charges>\"2026\"").contains("<script>alert(1)</script>")
                .contains("O'Brien&Sons<b>bold</b>");
    }

    @Test
    void aRecordWithNoStepsOrMissingFactsStillPrints() throws Exception {
        String text = textOf(renderer.render("T", "<p>Body</p>", "Note",
                new ApprovalRecord("T", "2.0", "i-1", null, "Name of bob", java.time.Instant.parse("2026-10-06T08:15:00Z"),
                        java.util.List.of(), null)));

        assertThat(text).contains("Approval record").contains("2.0").contains("i-1").contains("No step was recorded");
    }

    /** The footer of an approved PDF carries the document's name, which a person chose. */
    @Test
    void aDocumentNameWithQuotesPrintsInTheFooterAsItIs() throws Exception {
        String note = "Fees \"2026\" & O'Brien's {rates}; v2 · version 1.0";

        String text = textOf(renderer.render("T", "<p>Body</p>", note, record("T", "Step", "Person")));

        assertThat(text).contains(note + " · page 1 of 2").contains(note + " · page 2 of 2");
    }
}
