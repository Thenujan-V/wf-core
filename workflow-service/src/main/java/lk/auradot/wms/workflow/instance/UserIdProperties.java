package lk.auradot.wms.workflow.instance;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which JWT claim names the caller. {@code sub} by default; an embedding system that keys users by username
 * (AuraDOCS keys them by {@code preferred_username} without the email domain) sets the claim and the strip flag.
 */
@ConfigurationProperties("wms.workflow.security")
public record UserIdProperties(@DefaultValue("sub") String userIdClaim,
                               @DefaultValue("false") boolean stripEmailDomain) {
}
