package lk.auradot.wms.identity.sync;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
public class SyncController {

    private final FullSyncService sync;

    public SyncController(FullSyncService sync) {
        this.sync = sync;
    }

    @PostMapping("/sync")
    @PreAuthorize("hasRole('wms-admin')")
    public SyncResult sync() {
        try {
            return sync.runOnce().orElseThrow(
                    () -> ApiException.conflict("IDN_SYNC_IN_PROGRESS", "A directory sync is already running"));
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "IDN_KEYCLOAK_UNAVAILABLE",
                    "Keycloak could not be reached for directory sync");
        }
    }
}
