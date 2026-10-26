package lk.auradot.wms.common.security;

import lk.auradot.wms.common.EnableWmsService;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@EnableWmsService
class CommonTestApplication {

    @RestController
    static class ProbeController {
        @GetMapping("/probe/me")
        String me(Authentication authentication) {
            return authentication.getName();
        }

        @GetMapping("/probe/admin")
        @PreAuthorize("hasRole('wms-admin')")
        String admin() {
            return "ok";
        }
    }
}
