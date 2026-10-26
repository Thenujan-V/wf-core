package lk.auradot.wms.identity.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Users and realm roles from Keycloak; groups, departments and memberships from auraDOCS, joined to Keycloak users by
 * lower-cased email. auraDOCS groups become {@code /Name} and departments {@code /dept/Name}; a user's first department
 * becomes their {@code department} attribute, so the applier treats it like a Keycloak attribute.
 */
@Component
@ConditionalOnProperty(name = "wms.identity.source", havingValue = "AURADOCS")
public class AuraDocsDirectorySource implements DirectorySource {

    static final String GROUP_PREFIX = "adg-";
    static final String DEPARTMENT_PREFIX = "add-";
    static final int MAX_ID_LENGTH = 36;

    private final KeycloakAdminClient keycloak;
    private final AuraDocsDirectoryClient auraDocs;
    private final Map<String, List<String>> roleMap = new LinkedHashMap<>();

    public AuraDocsDirectorySource(KeycloakAdminClient keycloak, AuraDocsDirectoryClient auraDocs,
                                   AuraDocsProperties properties) {
        this.keycloak = keycloak;
        this.auraDocs = auraDocs;
        properties.roleMap().forEach((role, granted) -> roleMap.put(role.toLowerCase(Locale.ROOT), granted));
    }

    @Override
    public DirectorySnapshot load() {
        List<KcUser> users = KeycloakDirectorySource.people(keycloak);
        Map<String, List<String>> roles = KeycloakDirectorySource.realmRoles(keycloak, users);
        return merge(users, roles, auraDocs.fetch());
    }

    DirectorySnapshot merge(List<KcUser> users, Map<String, List<String>> realmRoles, AuraDocsDirectory directory) {
        Map<String, KcGroup> groups = new LinkedHashMap<>();
        for (AuraDocsDirectory.Group group : directory.groups()) {
            if (group.id() != null && !Boolean.FALSE.equals(group.active()) && !blank(group.name())) {
                groups.put(group.id(), new KcGroup(groupId(GROUP_PREFIX, group.id()), group.name(), "/" + group.name(), 0));
            }
        }
        Map<String, KcGroup> departments = new LinkedHashMap<>();
        for (AuraDocsDirectory.Department department : directory.departments()) {
            if (department.id() != null && !blank(department.name())) {
                departments.put(department.id(), new KcGroup(groupId(DEPARTMENT_PREFIX, department.id()),
                        department.name(), "/dept/" + department.name(), 0));
            }
        }
        Map<String, AuraDocsDirectory.User> byEmail = new LinkedHashMap<>();
        for (AuraDocsDirectory.User user : directory.users()) {
            if (!Boolean.FALSE.equals(user.active()) && !blank(user.email())) {
                byEmail.putIfAbsent(user.email().trim().toLowerCase(Locale.ROOT), user);
            }
        }

        Map<String, Set<String>> members = new LinkedHashMap<>();
        groups.values().forEach(g -> members.put(g.id(), new LinkedHashSet<>()));
        departments.values().forEach(g -> members.put(g.id(), new LinkedHashSet<>()));
        List<KcUser> merged = new ArrayList<>();
        Map<String, List<String>> roles = new LinkedHashMap<>();
        for (KcUser user : users) {
            Set<String> userRoles = new LinkedHashSet<>(realmRoles.getOrDefault(user.id(), List.of()));
            AuraDocsDirectory.User match = blank(user.email()) ? null
                    : byEmail.get(user.email().trim().toLowerCase(Locale.ROOT));
            if (match == null) {
                merged.add(user);
                roles.put(user.id(), List.copyOf(userRoles));
                continue;
            }
            for (String role : match.roles()) {
                if (role != null) {
                    userRoles.addAll(roleMap.getOrDefault(role.toLowerCase(Locale.ROOT), List.of()));
                }
            }
            for (String groupId : match.groupIds()) {
                KcGroup group = groups.get(groupId);
                if (group != null) {
                    members.get(group.id()).add(user.id());
                }
            }
            String department = null;
            for (String departmentId : match.departmentIds()) {
                KcGroup group = departments.get(departmentId);
                if (group != null) {
                    members.get(group.id()).add(user.id());
                    department = department == null ? group.name() : department;
                }
            }
            merged.add(withDepartment(user, department));
            roles.put(user.id(), List.copyOf(userRoles));
        }

        List<KcGroup> allGroups = new ArrayList<>(groups.values());
        allGroups.addAll(departments.values());
        Map<String, List<String>> memberIds = new LinkedHashMap<>();
        members.forEach((id, ids) -> memberIds.put(id, List.copyOf(ids)));
        return new DirectorySnapshot(merged, roles, allGroups, memberIds);
    }

    /** {@code prefix + id}, or a stable hash of the id when that would not fit app_group.id. */
    static String groupId(String prefix, String id) {
        String plain = prefix + id;
        if (plain.length() <= MAX_ID_LENGTH) {
            return plain;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return prefix + HexFormat.of().formatHex(digest).substring(0, MAX_ID_LENGTH - prefix.length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** auraDOCS owns the department of a matched user: its first department, or none. */
    private static KcUser withDepartment(KcUser user, String department) {
        Map<String, List<String>> attributes = new LinkedHashMap<>(user.attributes() == null ? Map.of() : user.attributes());
        if (department == null) {
            attributes.remove("department");
        } else {
            attributes.put("department", List.of(department));
        }
        return new KcUser(user.id(), user.username(), user.email(), user.firstName(), user.lastName(), user.enabled(),
                attributes);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
