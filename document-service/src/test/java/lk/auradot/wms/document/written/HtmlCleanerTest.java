package lk.auradot.wms.document.written;

import lk.auradot.wms.common.error.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HtmlCleanerTest {

    private final HtmlCleaner cleaner = new HtmlCleaner();

    @Test
    void keepsTheAllowedFormatting() {
        String html = "<h1>Policy</h1><p><strong>Bold</strong> <em>it</em> <u>under</u> <s>gone</s></p>"
                + "<ul><li>One</li></ul><ol><li>Two</li></ol><blockquote>Quote</blockquote><hr>"
                + "<table><tbody><tr><th colspan=\"2\">Head</th></tr><tr><td rowspan=\"1\">A</td><td>B</td></tr></tbody></table>";

        assertThat(cleaner.clean(html)).isEqualTo(html);
    }

    @Test
    void dropsScriptsHandlersStylesAndUnknownTagsButKeepsTheirText() {
        String cleaned = cleaner.clean("<p onclick=\"steal()\" style=\"color:red\" class=\"x\" id=\"y\">Hello</p>"
                + "<script>alert(1)</script><iframe src=\"https://evil.example\"></iframe>"
                + "<div><span>Kept text</span></div><h4>Small heading</h4>");

        assertThat(cleaned).isEqualTo("<p>Hello</p>Kept textSmall heading");
    }

    @Test
    void aCellSpansASmallWholeNumberOfRowsOrColumnsOrNoneAtAll() {
        String cleaned = cleaner.clean("<table><tbody><tr>"
                + "<td colspan=\"2\" rowspan=\"100\">kept</td>"
                + "<td rowspan=\"999999999\">huge</td>"
                + "<td rowspan=\"1;display:none\">styled</td>"
                + "<th colspan=\"0\">zero</th><th colspan=\"101\">over</th><th colspan=\" 3 \">spaced</th><th colspan=\"-1\">minus</th>"
                + "</tr></tbody></table>");

        assertThat(cleaned).isEqualTo("<table><tbody><tr>"
                + "<td colspan=\"2\" rowspan=\"100\">kept</td><td>huge</td><td>styled</td>"
                + "<th>zero</th><th>over</th><th colspan=\"3\">spaced</th><th>minus</th>"
                + "</tr></tbody></table>");
    }

    @Test
    void keepsOnlySafeLinks() {
        String cleaned = cleaner.clean("<a href=\"https://example.com/a\">ok</a>"
                + "<a href=\"mailto:bob@example.com\">mail</a>"
                + "<a href=\"javascript:alert(1)\">bad</a><a href=\"data:text/html,x\">worse</a>");

        assertThat(cleaned).contains("href=\"https://example.com/a\"").contains("href=\"mailto:bob@example.com\"")
                .doesNotContain("javascript").doesNotContain("data:");
        assertThat(cleaned).contains(">bad</a>").contains(">worse</a>");
    }

    @Test
    void imagesAreNotAllowedYet() {
        assertThat(cleaner.clean("<p>Before<img src=\"https://example.com/x.png\" onerror=\"x()\">After</p>"))
                .isEqualTo("<p>BeforeAfter</p>");
    }

    @Test
    void closesWhatWasLeftOpenAndTakesNothingForNothing() {
        assertThat(cleaner.clean("<p>Open <strong>bold")).isEqualTo("<p>Open <strong>bold</strong></p>");
        assertThat(cleaner.clean("")).isEmpty();
        assertThat(cleaner.clean(null)).isEmpty();
    }

    @Test
    void cleaningTwiceChangesNothingMore() {
        String once = cleaner.clean("<p>A &amp; B &lt; C</p><p>Line<br>break</p>");

        assertThat(cleaner.clean(once)).isEqualTo(once);
    }

    @Test
    void refusesATextOverOneMegabyte() {
        String big = "<p>" + "x".repeat(HtmlCleaner.MAX_BYTES) + "</p>";

        assertThatThrownBy(() -> cleaner.clean(big)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("DOC_TEXT_TOO_LARGE"));
    }
}
