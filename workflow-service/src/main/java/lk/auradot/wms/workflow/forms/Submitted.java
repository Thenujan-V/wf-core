package lk.auradot.wms.workflow.forms;

import java.util.Map;

/** A stored submission: its id and the cleaned values (hidden fields dropped, read-only fields at their baseline). */
public record Submitted(String id, Map<String, Object> data) {
}
