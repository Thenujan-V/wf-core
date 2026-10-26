package lk.auradot.wms.notification.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("wms.notification.push")
public record PushProperties(List<String> allowedOrigins) {

    public PushProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
