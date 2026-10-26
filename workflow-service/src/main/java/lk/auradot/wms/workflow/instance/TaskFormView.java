package lk.auradot.wms.workflow.instance;

import java.util.Map;

/** What the assignee's form needs: which form, field access at this step, and the current (non-hidden) values. */
public record TaskFormView(String formKey, int formVersion, Map<String, String> access, Map<String, Object> values) {
}
