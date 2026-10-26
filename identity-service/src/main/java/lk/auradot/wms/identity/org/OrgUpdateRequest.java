package lk.auradot.wms.identity.org;

import jakarta.validation.constraints.Size;

/** Full replacement of a user's org fields; null or blank clears a field. */
public record OrgUpdateRequest(String managerId, @Size(max = 255) String department, @Size(max = 255) String position) {
}
