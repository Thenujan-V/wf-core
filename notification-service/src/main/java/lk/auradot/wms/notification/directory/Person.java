package lk.auradot.wms.notification.directory;

/** What notifications need to know about someone: who they are, where to write to them, and whether they are active. */
public record Person(String id, String displayName, String email, boolean enabled) {
}
