package lk.auradot.wms.document.written;

import lk.auradot.wms.document.document.DocumentKind;
import lk.auradot.wms.document.document.DocumentRow;

/** Where a written document stands (written-documents spec §3). Worked out from the row, never stored. */
public enum WrittenState {
    DRAFT, IN_APPROVAL, APPROVED, REVISION;

    /** Null for an uploaded file. */
    public static WrittenState of(DocumentRow document, boolean hasDraft) {
        if (document.kind() != DocumentKind.WRITTEN) {
            return null;
        }
        if (document.linkedInstanceId() != null) {
            return IN_APPROVAL;
        }
        if (document.currentVersionId() == null) {
            return DRAFT;
        }
        return hasDraft ? REVISION : APPROVED;
    }

    public boolean editable() {
        return this == DRAFT || this == REVISION;
    }
}
