package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.workflow.directory.Directory;
import lk.auradot.wms.workflow.directory.DirectoryUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Who the caller is for workflow purposes: user id, admin flag, and the task pools they may claim from. */
@Component
public class UserContext {

    static final String ADMIN = "ROLE_wms-admin";

    private final Directory directory;

    public UserContext(Directory directory) {
        this.directory = directory;
    }

    public String userId(Authentication authentication) {
        return ((Jwt) authentication.getPrincipal()).getSubject();
    }

    public boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream().anyMatch(a -> ADMIN.equals(a.getAuthority()));
    }

    public List<String> candidates(Authentication authentication) {
        List<String> candidates = new ArrayList<>();
        try {
            directory.groupIdsOf(userId(authentication)).forEach(group -> candidates.add("group:" + group));
        } catch (DirectoryUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WF_DIRECTORY_UNAVAILABLE",
                    "The user directory is unavailable; please try again shortly");
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (authority.getAuthority().startsWith("ROLE_")) {
                candidates.add("role:" + authority.getAuthority().substring(5));
            }
        }
        return candidates;
    }
}
