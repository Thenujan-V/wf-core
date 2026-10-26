package lk.auradot.wms.workflow.monitor;

import lk.auradot.wms.workflow.instance.InstanceSummary;

import java.util.List;

public record InstancePage(List<InstanceSummary> items, long total, int page, int size) {
}
