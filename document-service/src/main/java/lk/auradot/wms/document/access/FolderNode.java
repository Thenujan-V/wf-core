package lk.auradot.wms.document.access;

public record FolderNode(String id, String parentId, String name, boolean inheritAcl) {
}
