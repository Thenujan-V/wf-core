package lk.auradot.wms.identity.sync;

import java.util.List;
import java.util.Map;

/** Everything read from Keycloak for one sync, fetched before any DB write so failures never half-apply. */
public record DirectorySnapshot(List<KcUser> users, Map<String, List<String>> rolesByUserId, List<KcGroup> groups,
                                Map<String, List<String>> memberIdsByGroupId) {
}
