package lk.auradot.wms.engine.runtime;

import java.util.List;
import java.util.Map;

/** The org chart as the engine needs it (Phase 2B implements it against identity-service). */
public interface AssigneeResolver {

    /** Managers of {@code userId}, nearest first, at most {@code levels} long. */
    List<String> managers(String userId, int levels);

    List<String> groupMembers(String groupId);

    List<String> roleMembers(String role);

    /** Profile fields exposed to conditions as {@code requester.*}; empty when unknown. */
    Map<String, Object> profile(String userId);

    /** False for ids that name nobody, or a user who can no longer sign in; tasks must not be given to them. */
    boolean isActiveUser(String userId);
}
