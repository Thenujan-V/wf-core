package lk.auradot.wms.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/** Browser origins allowed to call {@code /api/**} directly, e.g. the auraDOCS front. */
@ConfigurationProperties("wms.cors")
public record CorsProperties(@DefaultValue("http://localhost:4200") List<String> allowedOrigins) {
}
