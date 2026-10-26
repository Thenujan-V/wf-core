package lk.auradot.wms.identity.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The auraDOCS directory endpoint, used when {@code wms.identity.source=AURADOCS}. {@code roleMap} maps an auraDOCS
 * role name to the WMS roles it grants, on top of the user's Keycloak realm roles.
 */
@ConfigurationProperties("wms.identity.auradocs")
public record AuraDocsProperties(URI baseUrl,
                                 @DefaultValue("/integration/wms/directory") String directoryPath,
                                 Map<String, List<String>> roleMap,
                                 @DefaultValue("5s") Duration connectTimeout,
                                 @DefaultValue("30s") Duration readTimeout) {

    public AuraDocsProperties {
        roleMap = roleMap == null ? Map.of() : roleMap;
    }
}
