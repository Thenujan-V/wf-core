package lk.auradot.wms.workflow.forms;

import java.util.List;
import java.util.Map;

/** form-service rejected the values; {@link #errors()} is its {field, code, message} list, passed on unchanged. */
public class FormValidationException extends RuntimeException {

    private final List<Map<String, Object>> errors;

    public FormValidationException(List<Map<String, Object>> errors) {
        super("The form has " + errors.size() + " problem(s)");
        this.errors = List.copyOf(errors);
    }

    public List<Map<String, Object>> errors() {
        return errors;
    }
}
