package lk.auradot.wms.document;

import lk.auradot.wms.common.error.ApiException;

/** Folder, document and type names: 1-255 characters, no path separators or control characters. */
public final class Names {

    private Names() {
    }

    public static String check(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > 255 || name.equals(".") || name.equals("..")
                || name.chars().anyMatch(c -> c == '/' || c == '\\' || Character.isISOControl(c))) {
            throw ApiException.badRequest("DOC_NAME_INVALID",
                    "A name needs 1-255 characters without slashes or control characters");
        }
        return name;
    }
}
