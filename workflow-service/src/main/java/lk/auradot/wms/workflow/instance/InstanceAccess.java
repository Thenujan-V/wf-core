package lk.auradot.wms.workflow.instance;

/** What the caller may do with a request's documents (spec §4.5 access-check, used by document-service). */
public record InstanceAccess(String instanceId, String processKey, String status, boolean canView, boolean canAttach) {
}
