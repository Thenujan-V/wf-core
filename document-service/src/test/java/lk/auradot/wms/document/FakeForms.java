package lk.auradot.wms.document;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.forms.FormClient;
import lk.auradot.wms.document.forms.FormRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Stands in for form-service: a published form is a set of field keys, some of them required. */
public class FakeForms implements FormClient {

    private record Form(Set<String> fields, Set<String> required) {
    }

    private final Map<FormRef, Form> forms = new ConcurrentHashMap<>();

    public void reset() {
        forms.clear();
    }

    public void publish(FormRef ref, Set<String> fields, Set<String> required) {
        forms.put(ref, new Form(fields, required));
    }

    @Override
    public Map<String, Object> validate(FormRef ref, Map<String, Object> data, String token) {
        Form form = forms.get(ref);
        if (form == null) {
            throw ApiException.conflict("DOC_TYPE_FORM_MISSING", "The form of this document type is no longer available");
        }
        List<Map<String, String>> errors = new ArrayList<>();
        data.keySet().stream().filter(k -> !form.fields().contains(k))
                .forEach(k -> errors.add(Map.of("field", k, "code", "UNKNOWN_FIELD", "message", "No field " + k)));
        form.required().stream().filter(k -> data.get(k) == null || "".equals(data.get(k)))
                .forEach(k -> errors.add(Map.of("field", k, "code", "REQUIRED", "message", k + " is required")));
        if (!errors.isEmpty()) {
            throw FormClient.invalid(errors);
        }
        return new LinkedHashMap<>(data);
    }

    @Override
    public boolean isPublished(FormRef ref, String token) {
        return forms.containsKey(ref);
    }
}
