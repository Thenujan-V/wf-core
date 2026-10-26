package lk.auradot.wms.identity.jit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Provisions the caller before any handler runs. Several first requests from a new user arrive together,
 * so lock conflicts are retried; a lasting DB failure is logged and never fails the caller's request.
 */
@Component
public class JitProvisioningInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JitProvisioningInterceptor.class);
    private static final int MAX_ATTEMPTS = 3;

    private final JitProvisioningService provisioning;

    public JitProvisioningInterceptor(JitProvisioningService provisioning) {
        this.provisioning = provisioning;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            return true;
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                provisioning.ensureFresh(token.getToken());
                return true;
            } catch (ConcurrencyFailureException e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.warn("JIT provisioning for {} kept conflicting; continuing without it", token.getName(), e);
                }
            } catch (DataAccessException e) {
                log.warn("JIT provisioning for {} failed; continuing without it", token.getName(), e);
                return true;
            }
        }
        return true;
    }
}
