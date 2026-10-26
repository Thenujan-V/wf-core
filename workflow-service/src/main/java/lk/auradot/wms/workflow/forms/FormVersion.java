package lk.auradot.wms.workflow.forms;

import java.util.Map;

public record FormVersion(String key, int version, String status, Map<String, Object> definition) {
}
