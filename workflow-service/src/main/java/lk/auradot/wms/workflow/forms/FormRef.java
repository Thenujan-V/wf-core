package lk.auradot.wms.workflow.forms;

import java.util.Map;

/** A node's form: which published form version, and what each field allows at this step (unlisted = EDITABLE). */
public record FormRef(String key, int version, Map<String, String> access) {

    public FormRef {
        access = Map.copyOf(access);
    }
}
