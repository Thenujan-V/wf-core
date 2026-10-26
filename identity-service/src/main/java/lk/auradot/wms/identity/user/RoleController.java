package lk.auradot.wms.identity.user;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class RoleController {

    private final UserQueryService users;

    public RoleController(UserQueryService users) {
        this.users = users;
    }

    @GetMapping("/roles/{name}/members")
    public List<UserSummary> members(@PathVariable String name) {
        return users.roleMembers(name);
    }
}
