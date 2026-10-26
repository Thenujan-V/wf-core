package lk.auradot.wms.identity.org;

import jakarta.validation.Valid;
import lk.auradot.wms.identity.user.UserDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrgController {

    private final OrgAdminService orgAdmin;
    private final OrgSourceProperties orgSource;

    public OrgController(OrgAdminService orgAdmin, OrgSourceProperties orgSource) {
        this.orgAdmin = orgAdmin;
        this.orgSource = orgSource;
    }

    @PutMapping("/users/{id}/org")
    @PreAuthorize("hasRole('wms-admin')")
    public UserDetail update(@PathVariable String id, @Valid @RequestBody OrgUpdateRequest request) {
        return orgAdmin.update(id, request);
    }

    @GetMapping("/org/settings")
    @PreAuthorize("hasRole('wms-admin')")
    public OrgSettings settings() {
        return new OrgSettings(orgSource.manager(), orgSource.department(), orgSource.position());
    }
}
