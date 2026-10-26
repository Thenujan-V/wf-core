package lk.auradot.wms.document.folder;

import lk.auradot.wms.document.access.Permission;

import java.util.List;

/** A folder as the caller sees it: their permission, the path from the root, and the subfolders they can read. */
public record FolderView(String id, String name, String parentId, boolean inheritAcl, Permission permission,
                         List<PathItem> path, List<PathItem> children) {

    public record PathItem(String id, String name) {
    }
}
