package lk.auradot.wms.identity.sync;

import java.util.List;

/** The auraDOCS directory endpoint's response. Department ids are numbers there; they are read as text. */
public record AuraDocsDirectory(List<User> users, List<Group> groups, List<Department> departments) {

    public AuraDocsDirectory {
        users = users == null ? List.of() : users;
        groups = groups == null ? List.of() : groups;
        departments = departments == null ? List.of() : departments;
    }

    public record User(String userId, String email, String name, Boolean active, List<String> groupIds,
                       List<String> departmentIds, List<String> roles) {

        public User {
            groupIds = groupIds == null ? List.of() : groupIds;
            departmentIds = departmentIds == null ? List.of() : departmentIds;
            roles = roles == null ? List.of() : roles;
        }
    }

    public record Group(String id, String name, Boolean active) {
    }

    public record Department(String id, String name) {
    }
}
