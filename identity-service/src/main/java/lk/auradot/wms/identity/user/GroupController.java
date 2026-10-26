package lk.auradot.wms.identity.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class GroupController {

    private final UserQueryService users;

    public GroupController(UserQueryService users) {
        this.users = users;
    }

    @GetMapping("/groups")
    public List<GroupView> groups() {
        return users.groups();
    }

    @GetMapping("/groups/{id}/members")
    public List<UserSummary> members(@PathVariable String id) {
        return users.groupMembers(id);
    }
}
