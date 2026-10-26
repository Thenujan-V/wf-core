package lk.auradot.wms.document.storage;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** RFC 6266 Content-Disposition with an ASCII fallback name and the exact UTF-8 name (RFC 5987). */
public final class ContentDispositions {

    private ContentDispositions() {
    }

    public static String of(String fileName, boolean inline) {
        StringBuilder ascii = new StringBuilder();
        fileName.codePoints().forEach(c -> ascii.append(c >= 0x20 && c < 0x7f && c != '"' && c != '\\' ? (char) c : '_'));
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return (inline ? "inline" : "attachment") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
