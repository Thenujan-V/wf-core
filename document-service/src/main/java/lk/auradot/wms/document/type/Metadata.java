package lk.auradot.wms.document.type;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.forms.FormClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class Metadata {

    private final DocumentTypeStore types;
    private final FormClient forms;

    public Metadata(DocumentTypeStore types, FormClient forms) {
        this.types = types;
        this.forms = forms;
    }

    /** The values to store for a document of this type; only a type with a form takes metadata. */
    public Map<String, Object> check(String typeId, Map<String, Object> metadata, Caller caller) {
        Map<String, Object> data = metadata == null ? Map.of() : metadata;
        if (typeId == null) {
            if (!data.isEmpty()) {
                throw withoutType();
            }
            return new LinkedHashMap<>();
        }
        DocumentType type = types.find(typeId)
                .orElseThrow(() -> ApiException.badRequest("DOC_TYPE_NOT_FOUND", "No such document type"));
        if (type.form() == null) {
            if (!data.isEmpty()) {
                throw withoutType();
            }
            return new LinkedHashMap<>();
        }
        return forms.validate(type.form(), data, caller.token());
    }

    /** The document name and every metadata value, for the full-text index. */
    public static String searchText(String name, Map<String, Object> metadata) {
        StringBuilder text = new StringBuilder(name);
        append(text, metadata);
        return text.toString();
    }

    private static void append(StringBuilder text, Object value) {
        switch (value) {
            case null -> {
            }
            case Map<?, ?> map -> map.values().forEach(v -> append(text, v));
            case Collection<?> list -> list.forEach(v -> append(text, v));
            default -> text.append(' ').append(value);
        }
    }

    private static ApiException withoutType() {
        return ApiException.badRequest("DOC_METADATA_WITHOUT_TYPE", "Metadata needs a document type that defines fields");
    }
}
