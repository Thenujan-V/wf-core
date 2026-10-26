package lk.auradot.wms.notification.email;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("wms.notification.email")
public record EmailProperties(@DefaultValue("wms@localhost") String from, @DefaultValue("true") boolean dispatchEnabled,
                              @DefaultValue("PT5S") Duration dispatchInterval, @DefaultValue("5") int maxAttempts) {
}
