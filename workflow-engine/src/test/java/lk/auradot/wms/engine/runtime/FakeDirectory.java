package lk.auradot.wms.engine.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory org chart for engine tests. */
final class FakeDirectory implements AssigneeResolver {

    private final Map<String, String> managerOf = new HashMap<>();
    private final Map<String, List<String>> groups = new HashMap<>();
    private final Map<String, List<String>> roles = new HashMap<>();
    private final Map<String, String> departments = new HashMap<>();
    private final java.util.Set<String> inactive = new java.util.HashSet<>();

    /** Everyone is an active user unless marked here (unknown ids and deactivated accounts). */
    FakeDirectory inactive(String... users) {
        inactive.addAll(List.of(users));
        return this;
    }

    @Override
    public boolean isActiveUser(String userId) {
        return !inactive.contains(userId);
    }

    FakeDirectory manager(String user, String manager) {
        managerOf.put(user, manager);
        return this;
    }

    FakeDirectory group(String group, String... members) {
        groups.put(group, List.of(members));
        return this;
    }

    FakeDirectory role(String role, String... members) {
        roles.put(role, List.of(members));
        return this;
    }

    FakeDirectory department(String user, String department) {
        departments.put(user, department);
        return this;
    }

    @Override
    public List<String> managers(String userId, int levels) {
        List<String> chain = new ArrayList<>();
        String current = userId;
        for (int i = 0; i < levels; i++) {
            current = managerOf.get(current);
            if (current == null) {
                break;
            }
            chain.add(current);
        }
        return chain;
    }

    @Override
    public List<String> groupMembers(String groupId) {
        return groups.getOrDefault(groupId, List.of());
    }

    @Override
    public List<String> roleMembers(String role) {
        return roles.getOrDefault(role, List.of());
    }

    @Override
    public Map<String, Object> profile(String userId) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", userId);
        profile.put("department", departments.get(userId));
        profile.put("managerId", managerOf.get(userId));
        return profile;
    }
}
