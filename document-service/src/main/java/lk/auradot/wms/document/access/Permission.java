package lk.auradot.wms.document.access;

/** Folder permissions, each including the ones before it (MANAGE ⊃ DELETE ⊃ WRITE ⊃ READ). */
public enum Permission {
    READ, WRITE, DELETE, MANAGE;

    public boolean covers(Permission other) {
        return ordinal() >= other.ordinal();
    }
}
