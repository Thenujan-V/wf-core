package lk.auradot.wms.identity.user;

public record UserSummary(String id, String username, String displayName, String email, String department,
                          String position, String managerId, boolean enabled) {
}
