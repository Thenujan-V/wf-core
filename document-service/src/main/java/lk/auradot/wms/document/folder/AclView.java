package lk.auradot.wms.document.folder;

import lk.auradot.wms.document.access.AclEntry;

import java.util.List;

/** A folder's own entries, and (read-only) what it inherits from its ancestors. */
public record AclView(boolean inheritAcl, List<AclEntry> entries, List<AclEntry> inherited) {
}
