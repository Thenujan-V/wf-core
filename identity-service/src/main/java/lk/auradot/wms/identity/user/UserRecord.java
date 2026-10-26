package lk.auradot.wms.identity.user;

public record UserRecord(String id, String username, String email, String firstName, String lastName, boolean enabled) {
}
