package lk.auradot.wms.workflow.instance;

import jakarta.validation.constraints.Size;

/** Optional body of POST /instances/{id}/cancel: why the request is stopped (kept in its history and event). */
public record CancelRequest(@Size(max = 500) String reason) {
}
