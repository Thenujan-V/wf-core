package lk.auradot.wms.workflow;

import lk.auradot.wms.workflow.directory.Directory;
import lk.auradot.wms.workflow.directory.DirectoryUnavailableException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory org chart: carol → bob → alice, dave → alice. Role wms-admin = [alice], process-designer = [bob], finance = [frank].
 * Group g-fin = [bob, carol].
 */
public class StubDirectory implements Directory {

    private final Map<String, String> managerOf = new HashMap<>();
    private final Map<String, List<String>> groups = new HashMap<>();
    private final Map<String, List<String>> roles = new HashMap<>();
    private final Map<String, List<String>> userGroups = new HashMap<>();
    private final Set<String> inactive = new HashSet<>();
    private volatile boolean failing;

    public synchronized void reset() {
        managerOf.clear();
        groups.clear();
        roles.clear();
        userGroups.clear();
        inactive.clear();
        failing = false;
        managerOf.put("carol", "bob");
        managerOf.put("bob", "alice");
        managerOf.put("dave", "alice");
        roles.put("wms-admin", List.of("alice"));
        roles.put("process-designer", List.of("bob"));
        roles.put("finance", List.of("frank"));
        groups.put("g-fin", List.of("bob", "carol"));
        userGroups.put("bob", List.of("g-fin"));
        userGroups.put("carol", List.of("g-fin"));
    }

    public StubDirectory failing(boolean failing) {
        this.failing = failing;
        return this;
    }

    public synchronized StubDirectory inactive(String... users) {
        inactive.addAll(List.of(users));
        return this;
    }

    private void check() {
        if (failing) {
            throw new DirectoryUnavailableException("identity-service is down (test)", null);
        }
    }

    @Override
    public synchronized List<String> managers(String userId, int levels) {
        check();
        List<String> chain = new ArrayList<>();
        String current = userId;
        for (int i = 0; i < levels && (current = managerOf.get(current)) != null; i++) {
            chain.add(current);
        }
        return chain;
    }

    @Override
    public synchronized List<String> groupMembers(String groupId) {
        check();
        return groups.getOrDefault(groupId, List.of());
    }

    @Override
    public synchronized List<String> roleMembers(String role) {
        check();
        return roles.getOrDefault(role, List.of());
    }

    @Override
    public synchronized Map<String, Object> profile(String userId) {
        check();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", userId);
        profile.put("username", userId);
        profile.put("managerId", managerOf.get(userId));
        return profile;
    }

    @Override
    public synchronized boolean isActiveUser(String userId) {
        check();
        return !inactive.contains(userId);
    }

    @Override
    public synchronized List<String> groupIdsOf(String userId) {
        check();
        return userGroups.getOrDefault(userId, List.of());
    }
}
