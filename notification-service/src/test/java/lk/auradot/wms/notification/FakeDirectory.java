package lk.auradot.wms.notification;

import lk.auradot.wms.notification.directory.Directory;
import lk.auradot.wms.notification.directory.DirectoryUnavailableException;
import lk.auradot.wms.notification.directory.Person;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for identity-service: the people, groups and roles a test sets up, and whether it answers at all. */
public class FakeDirectory implements Directory {

    private final Map<String, Person> people = new ConcurrentHashMap<>();
    private final Map<String, List<String>> groups = new ConcurrentHashMap<>();
    private final Map<String, List<String>> roles = new ConcurrentHashMap<>();
    private final List<String> lookups = new CopyOnWriteArrayList<>();
    private volatile boolean down;

    public void reset() {
        people.clear();
        groups.clear();
        roles.clear();
        lookups.clear();
        down = false;
    }

    public void person(String id, String displayName, String email) {
        people.put(id, new Person(id, displayName, email, true));
    }

    public void disable(String id) {
        Person p = people.get(id);
        people.put(id, new Person(p.id(), p.displayName(), p.email(), false));
    }

    public void group(String groupId, String... userIds) {
        groups.put(groupId, List.of(userIds));
    }

    public void role(String role, String... userIds) {
        roles.put(role, List.of(userIds));
    }

    /** identity-service cannot answer. */
    public void down(boolean down) {
        this.down = down;
    }

    /** The ids someone was looked up by, in order. */
    public List<String> lookups() {
        return lookups;
    }

    @Override
    public Optional<Person> person(String userId) {
        check();
        lookups.add(userId);
        return Optional.ofNullable(people.get(userId));
    }

    @Override
    public List<Person> groupMembers(String groupId) {
        check();
        return members(groups.getOrDefault(groupId, List.of()));
    }

    @Override
    public List<Person> roleMembers(String role) {
        check();
        return members(roles.getOrDefault(role, List.of()));
    }

    private List<Person> members(List<String> ids) {
        return ids.stream().map(people::get).filter(Objects::nonNull).toList();
    }

    private void check() {
        if (down) {
            throw new DirectoryUnavailableException("identity-service is down (test)", null);
        }
    }

    @Override
    public String toString() {
        return "FakeDirectory" + Arrays.asList(people.keySet(), groups.keySet(), roles.keySet());
    }
}
