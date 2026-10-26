package lk.auradot.wms.document.access;

public record AclEntry(PrincipalType principalType, String principalId, Permission permission) {
}
