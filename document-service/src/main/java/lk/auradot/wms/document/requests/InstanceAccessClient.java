package lk.auradot.wms.document.requests;

import lk.auradot.wms.document.access.Caller;

import java.util.Optional;

/** Spec §2.2 "document → workflow (can user view instance X — cached ~60s)". Empty: the caller may not see it. */
public interface InstanceAccessClient {

    /** The answer, possibly remembered from the last minute. */
    Optional<InstanceAccess> access(String instanceId, Caller caller);

    /**
     * Asks again and remembers the new answer. For attaching: claiming or finishing a task changes it at once, and a
     * person who just claimed a task should not be refused for a minute.
     */
    Optional<InstanceAccess> fresh(String instanceId, Caller caller);
}
