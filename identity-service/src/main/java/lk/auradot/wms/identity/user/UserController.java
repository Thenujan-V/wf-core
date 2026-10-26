package lk.auradot.wms.identity.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class UserController {

    private final UserQueryService users;

    public UserController(UserQueryService users) {
        this.users = users;
    }

    @GetMapping("/me")
    public UserDetail me(@AuthenticationPrincipal Jwt jwt) {
        return users.requireDetail(jwt.getSubject());
    }

    @GetMapping("/users")
    public PageResponse<UserSummary> search(@RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return users.search(q, page, size, includeDisabled);
    }

    @GetMapping("/users/{id}")
    public UserDetail user(@PathVariable String id) {
        return users.requireDetail(id);
    }

    @GetMapping("/users/{id}/managers")
    public List<UserSummary> managers(@PathVariable String id, @RequestParam(defaultValue = "1") int levels) {
        return users.managers(id, levels);
    }

    @GetMapping("/users/{id}/reports")
    public ReportsPage reports(@PathVariable String id,
                                     @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return users.reports(id, includeDisabled);
    }
}
