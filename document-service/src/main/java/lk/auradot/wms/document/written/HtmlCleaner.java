package lk.auradot.wms.document.written;

import lk.auradot.wms.common.error.ApiException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * What a written document's text may contain (written-documents spec §9). The browser's editor is not trusted: every
 * save goes through here, and what comes out is what is stored, shown to others and later made into a PDF.
 */
@Component
public class HtmlCleaner {

    public static final int MAX_BYTES = 1_048_576;

    private static final Safelist ALLOWED = new Safelist()
            .addTags("p", "h1", "h2", "h3", "ul", "ol", "li", "blockquote", "br", "hr",
                    "table", "thead", "tbody", "tr", "th", "td", "strong", "em", "u", "s", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("td", "colspan", "rowspan");

    private static final Document.OutputSettings AS_WRITTEN = new Document.OutputSettings().prettyPrint(false);

    /** The text with everything outside the allowed formatting removed; the words inside removed tags are kept. */
    public String clean(String html) {
        Document kept = new Cleaner(ALLOWED).clean(Jsoup.parseBodyFragment(html == null ? "" : html, ""));
        Spans.normalise(kept.body());
        String cleaned = kept.outputSettings(AS_WRITTEN).body().html();
        if (cleaned.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw ApiException.badRequest("DOC_TEXT_TOO_LARGE", "This document is too long to save; shorten it or split it in two");
        }
        return cleaned;
    }
}
