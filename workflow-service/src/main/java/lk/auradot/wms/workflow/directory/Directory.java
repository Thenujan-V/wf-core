package lk.auradot.wms.workflow.directory;

import lk.auradot.wms.engine.runtime.AssigneeResolver;

import java.util.List;

/** The engine's view of the org chart, plus what the inbox needs to know about a user. */
public interface Directory extends AssigneeResolver {

    /** Ids of the groups the user belongs to (for claimable "group:<id>" tasks). */
    List<String> groupIdsOf(String userId);
}
