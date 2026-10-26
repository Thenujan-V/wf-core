package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.engine.definition.ValidationError;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Spec §4.1: referenced forms and form versions exist and are published; field access must make sense. */
@Component
public class FormReferenceChecks {

    private final FormClient forms;

    public FormReferenceChecks(FormClient forms) {
        this.forms = forms;
    }

    public List<ValidationError> check(Map<String, FormRef> references) {
        List<ValidationError> errors = new ArrayList<>();
        references.forEach((nodeId, ref) -> {
            Optional<FormVersion> version;
            try {
                version = forms.version(ref.key(), ref.version());
            } catch (FormServiceUnavailableException e) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WF_FORM_SERVICE_UNAVAILABLE",
                        "The forms this process uses cannot be checked right now; please try again shortly");
            }
            if (version.isEmpty()) {
                errors.add(new ValidationError(nodeId, "FORM_NOT_FOUND",
                        "Form '" + ref.key() + "' has no version " + ref.version()));
                return;
            }
            if (!"PUBLISHED".equals(version.get().status())) {
                errors.add(new ValidationError(nodeId, "FORM_RETIRED",
                        "Form '" + ref.key() + "' version " + ref.version() + " is retired; choose a published version"));
            }
            Map<String, List<String>> fields = inputFields(version.get().definition());
            ref.access().keySet().stream().filter(key -> !fields.containsKey(key)).forEach(key -> errors.add(
                    new ValidationError(nodeId, "ACCESS_UNKNOWN_FIELD", "Form '" + ref.key() + "' has no field '" + key + "'")));
            fields.forEach((field, uses) -> {
                if ("HIDDEN".equals(ref.access().get(field))) {
                    return; // a hidden field's own rules never run in the browser
                }
                uses.stream().filter(used -> "HIDDEN".equals(ref.access().get(used))).forEach(used -> errors.add(
                        new ValidationError(nodeId, "ACCESS_HIDDEN_IN_CONDITION", "Field '" + field + "' depends on '"
                                + used + "', which is hidden at this step; show it read-only or hide both")));
            });
        });
        return errors;
    }

    /** Input field keys, each with the form fields its show-when / required-when rules use. */
    public static Map<String, List<String>> inputFields(Map<String, Object> definition) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        if (definition.get("fields") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> field && field.get("key") instanceof String key
                        && !"heading".equals(field.get("type")) && !"display".equals(field.get("type"))) {
                    List<String> uses = new ArrayList<>();
                    collectFormVariables(field.get("visibleWhen"), uses);
                    collectFormVariables(field.get("requiredWhen"), uses);
                    fields.put(key, uses);
                }
            }
        }
        return fields;
    }

    private static void collectFormVariables(Object node, List<String> into) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("var") instanceof String variable && variable.startsWith("form.")) {
                into.add(variable.substring("form.".length()));
            }
            map.values().forEach(value -> collectFormVariables(value, into));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> collectFormVariables(value, into));
        }
    }

    /** Keys of the form's file fields (spec §5 "file upload (document references)"). */
    public static Set<String> fileFields(Map<String, Object> definition) {
        Set<String> keys = new LinkedHashSet<>();
        if (definition.get("fields") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> field && "file".equals(field.get("type")) && field.get("key") instanceof String key) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }
}
