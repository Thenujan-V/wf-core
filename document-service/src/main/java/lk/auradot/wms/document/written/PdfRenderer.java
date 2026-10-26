package lk.auradot.wms.document.written;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Level;

/**
 * Turns the cleaned text of a written document into a PDF (written-documents spec §11): A4, 25 mm margins, and on
 * every page a note and the page number. It knows nothing about documents, requests or storage.
 */
@Component
public class PdfRenderer {

    static {
        // The library reports every render at INFO through java.util.logging; only problems are worth a line.
        XRLog.listRegisteredLoggers().forEach(logger -> XRLog.setLevel(logger, Level.WARNING));
    }

    private static final String STYLE = """
            @page {
              size: A4;
              margin: 25mm;
              @bottom-center { content: "%s · page " counter(page) " of " counter(pages); font-family: sans-serif; font-size: 9pt; color: #555555; }
            }
            body { font-family: sans-serif; font-size: 11pt; line-height: 1.5; color: #1a1a1a; word-wrap: break-word; }
            h1 { font-size: 20pt; margin: 14pt 0 6pt; }
            h2 { font-size: 15pt; margin: 12pt 0 6pt; }
            h3 { font-size: 12pt; margin: 10pt 0 4pt; }
            p { margin: 6pt 0; }
            blockquote { margin: 6pt 0; padding-left: 9pt; border-left: 2pt solid #999999; color: #444444; }
            table { border-collapse: collapse; width: 100%%; margin: 8pt 0; -fs-table-paginate: paginate; }
            th, td { border: 0.5pt solid #777777; padding: 3pt 5pt; text-align: left; vertical-align: top; }
            th { background-color: #eeeeee; font-weight: bold; }
            hr { border: 0; border-top: 0.5pt solid #777777; margin: 10pt 0; }
            a { color: #1a1a1a; text-decoration: underline; }
            .record { page-break-before: always; }
            .record table.facts th { width: 32%%; }
            """;

    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("d MMM uuuu, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public byte[] render(String title, String bodyHtml, String footerNote) {
        return render(title, bodyHtml, footerNote, null);
    }

    /** With a record, the PDF ends with a page that says who approved the text and when. */
    public byte[] render(String title, String bodyHtml, String footerNote, ApprovalRecord record) {
        Document page = Jsoup.parse("<html><head><title></title><style></style></head><body></body></html>");
        page.title(title == null ? "" : title);
        page.head().selectFirst("style").appendChild(new DataNode(STYLE.formatted(cssString(footerNote))));
        page.body().html(bodyHtml == null ? "" : bodyHtml);
        // Texts stored before spans were checked on save are still out there; the renderer trusts none of them.
        Spans.normalise(page.body());
        if (record != null) {
            appendRecord(page.body(), record);
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            // The text names nothing the renderer should fetch: no address is ever resolved, so nothing is loaded.
            builder.useUriResolver((baseUri, uri) -> null);
            builder.withW3cDocument(new W3CDom().fromJsoup(page), null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("The document could not be made into a PDF", e);
        }
    }

    /** Built element by element with text(), so no name or reference can be read as markup. */
    private static void appendRecord(Element body, ApprovalRecord record) {
        Element section = body.appendElement("div").addClass("record");
        section.appendElement("h1").text("Approval record");
        Element facts = section.appendElement("table").addClass("facts").appendElement("tbody");
        fact(facts, "Document", record.documentName());
        fact(facts, "Version", record.version());
        fact(facts, "Request", record.reference());
        fact(facts, "Process", record.processName());
        fact(facts, "Sent for approval by", record.sentBy() + (record.sentAt() == null ? "" : ", " + WHEN.format(record.sentAt())));
        fact(facts, "Request ended", record.endedAt() == null ? null : WHEN.format(record.endedAt()));

        section.appendElement("h2").text("Decisions");
        if (record.steps().isEmpty()) {
            section.appendElement("p").text("No step was recorded for this request.");
            return;
        }
        Element table = section.appendElement("table");
        Element head = table.appendElement("thead").appendElement("tr");
        for (String title : new String[] {"Step", "Decided by", "Decision", "Date"}) {
            head.appendElement("th").text(title);
        }
        Element rows = table.appendElement("tbody");
        for (ApprovalRecord.Step step : record.steps()) {
            Element row = rows.appendElement("tr");
            row.appendElement("td").text(orDash(step.name()));
            row.appendElement("td").text(orDash(step.person()));
            row.appendElement("td").text(orDash(step.decision()));
            row.appendElement("td").text(step.at() == null ? "—" : WHEN.format(step.at()));
        }
    }

    private static void fact(Element facts, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Element row = facts.appendElement("tr");
        row.appendElement("th").text(label);
        row.appendElement("td").text(value);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /** The note inside a CSS string: backslashes and quotes escaped, line breaks dropped. */
    private static String cssString(String note) {
        return (note == null ? "" : note).replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\r\\n]+", " ");
    }
}
