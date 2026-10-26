package lk.auradot.wms.identity.org;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.identity.user.UserDetail;
import lk.auradot.wms.identity.user.UserQueryService;
import lk.auradot.wms.identity.user.UserSummary;
import lk.auradot.wms.identity.user.UserWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

@Service
public class OrgAdminService {

    private final UserQueryService users;
    private final UserWriter writer;
    private final OrgSourceProperties orgSource;

    public OrgAdminService(UserQueryService users, UserWriter writer, OrgSourceProperties orgSource) {
        this.users = users;
        this.writer = writer;
        this.orgSource = orgSource;
    }

    @Transactional
    public UserDetail update(String userId, OrgUpdateRequest request) {
        UserSummary current = users.findSummary(userId)
                .orElseThrow(() -> ApiException.notFound("IDN_USER_NOT_FOUND", "User not found"));
        String managerId = blankToNull(request.managerId());
        String department = blankToNull(request.department());
        String position = blankToNull(request.position());

        requireEditable(OrgField.MANAGER, current.managerId(), managerId);
        requireEditable(OrgField.DEPARTMENT, current.department(), department);
        requireEditable(OrgField.POSITION, current.position(), position);

        if (managerId != null && !managerId.equals(current.managerId())) {
            validateManager(userId, managerId);
        }
        writer.updateOrg(userId, OrgField.MANAGER, managerId);
        writer.updateOrg(userId, OrgField.DEPARTMENT, department);
        writer.updateOrg(userId, OrgField.POSITION, position);
        return users.requireDetail(userId);
    }

    private void requireEditable(OrgField field, String currentValue, String requestedValue) {
        if (orgSource.sourceOf(field) == OrgSource.KEYCLOAK && !Objects.equals(currentValue, requestedValue)) {
            throw ApiException.conflict("IDN_ORG_FIELD_MANAGED_BY_KEYCLOAK",
                    field.apiName() + " is managed in Keycloak for this instance");
        }
    }

    private void validateManager(String userId, String managerId) {
        if (managerId.equals(userId)) {
            throw ApiException.badRequest("IDN_MANAGER_SELF", "A user cannot be their own manager");
        }
        if (users.findSummary(managerId).isEmpty()) {
            throw ApiException.badRequest("IDN_MANAGER_NOT_FOUND", "Manager not found");
        }
        Set<String> visited = new HashSet<>();
        String next = managerId;
        while (next != null && visited.add(next)) {
            if (next.equals(userId)) {
                throw ApiException.badRequest("IDN_MANAGER_CYCLE", "This manager would create a reporting cycle");
            }
            next = users.findSummary(next).map(UserSummary::managerId).orElse(null);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
