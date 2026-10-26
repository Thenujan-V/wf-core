package lk.auradot.wms.identity.user;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class UserQueryService {

    public static final String SUMMARY_COLUMNS =
            "u.id, u.username, u.email, u.first_name, u.last_name, u.enabled, u.department, u.position_title, u.manager_id";

    private final JdbcClient jdbc;

    public UserQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserSummary> findSummary(String id) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM app_user u WHERE u.id = :id")
                .param("id", id)
                .query(UserQueryService::mapSummary)
                .optional();
    }

    public UserDetail requireDetail(String id) {
        UserSummary summary = findSummary(id)
                .orElseThrow(() -> ApiException.notFound("IDN_USER_NOT_FOUND", "User not found"));
        List<String> roles = jdbc.sql("SELECT role_name FROM user_role WHERE user_id = :id ORDER BY role_name")
                .param("id", id).query(String.class).list();
        List<GroupView> groups = jdbc.sql("""
                        SELECT g.id, g.name, g.path FROM app_group g
                        JOIN user_group ug ON ug.group_id = g.id
                        WHERE ug.user_id = :id ORDER BY g.path""")
                .param("id", id)
                .query((rs, row) -> new GroupView(rs.getString("id"), rs.getString("name"), rs.getString("path")))
                .list();
        return UserDetail.of(summary, roles, groups);
    }

    public PageResponse<UserSummary> search(String q, int page, int size, boolean includeDisabled) {
        if (page < 0) {
            throw ApiException.badRequest("IDN_INVALID_PAGE", "page must be 0 or greater");
        }
        if (size < 1 || size > 100) {
            throw ApiException.badRequest("IDN_INVALID_PAGE_SIZE", "size must be between 1 and 100");
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (!includeDisabled) {
            where.append(" AND u.enabled = TRUE");
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (u.username LIKE :pattern ESCAPE '!' OR u.email LIKE :pattern ESCAPE '!'"
                    + " OR CONCAT_WS(' ', u.first_name, u.last_name) LIKE :pattern ESCAPE '!')");
            params.put("pattern", "%" + escapeLike(q.trim()) + "%");
        }
        long total = jdbc.sql("SELECT COUNT(*) FROM app_user u" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<UserSummary> items = jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM app_user u" + where
                        + " ORDER BY u.username, u.id LIMIT :limit OFFSET :offset")
                .params(params)
                .query(UserQueryService::mapSummary)
                .list();
        return new PageResponse<>(items, page, size, total);
    }

    /** Manager chain, nearest first; stops at the top, a missing user, or a cycle. */
    public List<UserSummary> managers(String userId, int levels) {
        if (levels < 1 || levels > 10) {
            throw ApiException.badRequest("IDN_INVALID_LEVELS", "levels must be between 1 and 10");
        }
        UserSummary current = findSummary(userId)
                .orElseThrow(() -> ApiException.notFound("IDN_USER_NOT_FOUND", "User not found"));
        List<UserSummary> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>(Set.of(userId));
        String next = current.managerId();
        while (next != null && chain.size() < levels && visited.add(next)) {
            UserSummary manager = findSummary(next).orElse(null);
            if (manager == null) {
                break;
            }
            chain.add(manager);
            next = manager.managerId();
        }
        return chain;
    }

    static final int MAX_REPORTS = 500;

    /** The people whose manager is this person, by username; a very long list is cut, and says how long it is. */
    public ReportsPage reports(String userId, boolean includeDisabled) {
        if (findSummary(userId).isEmpty()) {
            throw ApiException.notFound("IDN_USER_NOT_FOUND", "User not found");
        }
        String where = " FROM app_user u WHERE u.manager_id = :id" + (includeDisabled ? "" : " AND u.enabled = TRUE");
        long total = jdbc.sql("SELECT COUNT(*)" + where).param("id", userId).query(Long.class).single();
        List<UserSummary> items = jdbc.sql("SELECT " + SUMMARY_COLUMNS + where + " ORDER BY u.username, u.id LIMIT :limit")
                .param("id", userId).param("limit", MAX_REPORTS)
                .query(UserQueryService::mapSummary)
                .list();
        return new ReportsPage(items, total);
    }

    public List<GroupView> groups() {
        return jdbc.sql("SELECT id, name, path FROM app_group ORDER BY path")
                .query((rs, row) -> new GroupView(rs.getString("id"), rs.getString("name"), rs.getString("path")))
                .list();
    }

    public List<UserSummary> groupMembers(String groupId) {
        boolean exists = jdbc.sql("SELECT COUNT(*) FROM app_group WHERE id = :id")
                .param("id", groupId).query(Integer.class).single() > 0;
        if (!exists) {
            throw ApiException.notFound("IDN_GROUP_NOT_FOUND", "Group not found");
        }
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM app_user u JOIN user_group ug ON ug.user_id = u.id"
                        + " WHERE ug.group_id = :id ORDER BY u.username, u.id")
                .param("id", groupId)
                .query(UserQueryService::mapSummary)
                .list();
    }

    public List<UserSummary> roleMembers(String role) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM app_user u JOIN user_role ur ON ur.user_id = u.id"
                        + " WHERE ur.role_name = :role AND u.enabled = TRUE ORDER BY u.username, u.id")
                .param("role", role)
                .query(UserQueryService::mapSummary)
                .list();
    }

    static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static UserSummary mapSummary(ResultSet rs, int row) throws SQLException {
        String username = rs.getString("username");
        return new UserSummary(
                rs.getString("id"),
                username,
                displayName(rs.getString("first_name"), rs.getString("last_name"), username),
                rs.getString("email"),
                rs.getString("department"),
                rs.getString("position_title"),
                rs.getString("manager_id"),
                rs.getBoolean("enabled"));
    }

    static String displayName(String firstName, String lastName, String username) {
        String full = Stream.of(firstName, lastName)
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(Collectors.joining(" "));
        return full.isEmpty() ? username : full;
    }
}
