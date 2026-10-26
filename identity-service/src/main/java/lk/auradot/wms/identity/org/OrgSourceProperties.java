package lk.auradot.wms.identity.org;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("wms.identity.org-source")
public record OrgSourceProperties(@DefaultValue("KEYCLOAK") OrgSource manager,
                                  @DefaultValue("KEYCLOAK") OrgSource department,
                                  @DefaultValue("KEYCLOAK") OrgSource position) {

    public OrgSource sourceOf(OrgField field) {
        return switch (field) {
            case MANAGER -> manager;
            case DEPARTMENT -> department;
            case POSITION -> position;
        };
    }
}
