package lk.auradot.wms.identity.user;

import java.util.List;

public record UserDetail(String id, String username, String displayName, String email, String department,
                         String position, String managerId, boolean enabled, List<String> roles,
                         List<GroupView> groups) {

    static UserDetail of(UserSummary s, List<String> roles, List<GroupView> groups) {
        return new UserDetail(s.id(), s.username(), s.displayName(), s.email(), s.department(), s.position(),
                s.managerId(), s.enabled(), roles, groups);
    }
}
