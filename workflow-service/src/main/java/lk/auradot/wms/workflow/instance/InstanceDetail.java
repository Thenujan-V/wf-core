package lk.auradot.wms.workflow.instance;

import java.util.List;
import java.util.Map;

public record InstanceDetail(InstanceSummary instance, Map<String, Object> variables, List<TaskView> tasks) {
}
