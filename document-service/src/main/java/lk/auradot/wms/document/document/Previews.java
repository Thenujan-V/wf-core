package lk.auradot.wms.document.document;

import java.util.Locale;
import java.util.Set;

/** Spec §6: PDF, images and text are shown inline; anything else (including HTML and SVG) only downloads. */
public final class Previews {

    private static final Set<String> INLINE = Set.of("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp",
            "text/plain");

    private Previews() {
    }

    public static boolean inline(String mime) {
        String base = mime == null ? "" : mime.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return INLINE.contains(base);
    }
}
