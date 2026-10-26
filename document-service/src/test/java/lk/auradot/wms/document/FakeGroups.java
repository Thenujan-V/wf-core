package lk.auradot.wms.document;

import lk.auradot.wms.document.access.GroupLookup;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Test groups: bob and carol are in g-fin unless a test says otherwise. */
public class FakeGroups implements GroupLookup {

    private final Map<String, Set<String>> groups = new ConcurrentHashMap<>();

    public FakeGroups() {
        reset();
    }

    public void reset() {
        groups.clear();
        set("bob", "g-fin");
        set("carol", "g-fin");
    }

    public void set(String userId, String... groupIds) {
        groups.put(userId, Set.of(groupIds));
    }

    @Override
    public Set<String> groupIds(String userId, String token) {
        return groups.getOrDefault(userId, Set.of());
    }
}
