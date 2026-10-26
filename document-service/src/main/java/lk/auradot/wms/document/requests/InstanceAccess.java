package lk.auradot.wms.document.requests;

/** workflow-service's answer for the caller: may they see the request, and may they still add documents to it. */
public record InstanceAccess(String instanceId, String processKey, String status, boolean canView, boolean canAttach) {
}
