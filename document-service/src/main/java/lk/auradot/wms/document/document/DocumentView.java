package lk.auradot.wms.document.document;

import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.written.WrittenState;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A document with its metadata, the caller's permission, and every version (newest first). {@code linkedInstanceId}
 * is the request it belongs to; {@code inLibrary} says whether the caller can also reach it through its folder.
 * A written document also says where it stands ({@code state}) and whether a text is being worked on.
 */
public record DocumentView(String id, String folderId, String name, String typeId, Map<String, Object> metadata,
                           String checkedOutBy, Instant checkedOutAt, String createdBy, Instant createdAt, Instant updatedAt,
                           Permission permission, String currentVersionId, List<VersionView> versions,
                           String linkedInstanceId, boolean inLibrary,
                           DocumentKind kind, WrittenState state, boolean hasDraft) {
}
