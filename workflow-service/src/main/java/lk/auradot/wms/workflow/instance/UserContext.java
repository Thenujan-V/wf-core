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
    private final UserIdProperties ids;

    public UserContext(Directory directory, UserIdProperties ids) {
        this.directory = directory;
        this.ids = ids;
    }

    /** The configured id claim, falling back to {@code sub} when the token does not carry it. */
    public String userId(Authentication authentication) {
        Jwt jwt = (Jwt) authentication.getPrincipal();
        String id = jwt.getClaimAsString(ids.userIdClaim());
        if (id == null || id.isBlank()) {
            return jwt.getSubject();
        }
        int at = id.indexOf('@');
        return ids.stripEmailDomain() && at > 0 ? id.substring(0, at) : id;
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
