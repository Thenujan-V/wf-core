package lk.auradot.wms.document.access;

import java.util.Set;

/** The identity-service group ids of a user, for GROUP entries in folder ACLs. */
public interface GroupLookup {

    Set<String> groupIds(String userId, String token);
}
