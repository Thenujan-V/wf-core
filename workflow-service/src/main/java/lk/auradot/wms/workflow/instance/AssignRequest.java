package lk.auradot.wms.workflow.instance;

import jakarta.validation.constraints.NotBlank;

public record AssignRequest(@NotBlank String toUserId) {
}
