package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.engine.definition.ValidationError;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads the optional {@code form} setting of start nodes and user tasks from a process graph. */
public final class ProcessForms {

    public record Result(Map<String, FormRef> forms, List<ValidationError> errors) {
    }

    private static final Set<String> ACCESS = Set.of("EDITABLE", "READONLY", "HIDDEN");
    /** form-service's key format; anything else could never be found there. */
    private static final Pattern FORM_KEY = Pattern.compile("[a-z0-9][a-z0-9-]{1,99}");
    private static final String SHAPE = "A form setting needs a form key and a version number";

    private ProcessForms() {
    }

    public static Result read(Map<String, Object> graph) {
        Map<String, FormRef> forms = new LinkedHashMap<>();
        List<ValidationError> errors = new ArrayList<>();
        if (!(graph.get("nodes") instanceof List<?> nodes)) {
            return new Result(forms, errors); // DefinitionJson already reports a missing node list
        }
        for (Object item : nodes) {
            if (!(item instanceof Map<?, ?> node) || node.get("form") == null) {
                continue;
            }
            String id = String.valueOf(node.get("id"));
            Object type = node.get("type");
            if (!"START".equals(type) && !"USER_TASK".equals(type)) {
                errors.add(new ValidationError(id, "FORM_NOT_ALLOWED", "Only the start node and user tasks can have a form"));
                continue;
            }
            if (!(node.get("form") instanceof Map<?, ?> form) || !(form.get("key") instanceof String key) || !FORM_KEY.matcher(key).matches()
                    || !(form.get("version") instanceof Number version) || version.intValue() < 1
                    || version.doubleValue() != version.intValue()) {
                errors.add(new ValidationError(id, "FORM_REF_INVALID", SHAPE));
                continue;
            }
            Map<String, String> access = new LinkedHashMap<>();
            Object rawAccess = form.get("access");
            if (rawAccess != null) {
                if ("START".equals(type)) {
                    errors.add(new ValidationError(id, "FORM_REF_INVALID",
                            "The requester fills in the whole start form; field access applies to tasks only"));
                    continue;
                }
                if (!(rawAccess instanceof Map<?, ?> map) || !map.values().stream().allMatch(ACCESS::contains)) {
                    errors.add(new ValidationError(id, "FORM_REF_INVALID",
                            "Field access must map field keys to EDITABLE, READONLY or HIDDEN"));
                    continue;
                }
                map.forEach((field, level) -> access.put(String.valueOf(field), (String) level));
            }
            forms.put(id, new FormRef(key, version.intValue(), access));
        }
        return new Result(forms, errors);
    }
}
