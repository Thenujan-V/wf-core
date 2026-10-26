package lk.auradot.wms.workflow.forms;

import java.util.Map;

/** Body of form-service's POST /submissions. */
public record SubmitForm(String formKey, int version, Map<String, Object> data, Map<String, String> access,
                         Map<String, Object> baseline, String submittedBy, String contextRef) {
}
