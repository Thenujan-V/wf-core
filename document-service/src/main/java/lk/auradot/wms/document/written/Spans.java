package lk.auradot.wms.document.written;

import org.jsoup.nodes.Element;

/**
 * How many rows or columns a table cell covers. The PDF renderer reads these two attributes as part of the cell's
 * style, so anything but a small whole number could restyle or hide the cell in the PDF, or ask it to lay out
 * millions of rows. A span that is not a whole number from 1 to {@link #MAX} is removed, leaving an ordinary cell.
 */
final class Spans {

    static final int MAX = 100;

    private Spans() {
    }

    /** Fixes every cell under (and including) the element. */
    static void normalise(Element root) {
        for (Element cell : root.select("td, th")) {
            for (String attribute : new String[] {"colspan", "rowspan"}) {
                if (cell.hasAttr(attribute)) {
                    String value = cell.attr(attribute).strip();
                    if (value.matches("[0-9]{1,3}") && Integer.parseInt(value) >= 1 && Integer.parseInt(value) <= MAX) {
                        cell.attr(attribute, String.valueOf(Integer.parseInt(value)));
                    } else {
                        cell.removeAttr(attribute);
                    }
                }
            }
        }
    }
}
