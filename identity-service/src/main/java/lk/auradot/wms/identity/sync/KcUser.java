package lk.auradot.wms.identity.sync;

import java.util.List;
import java.util.Map;

public record KcUser(String id, String username, String email, String firstName, String lastName, Boolean enabled,
                     Map<String, List<String>> attributes) {

    /** First non-blank value of a user attribute, trimmed; null when absent. */
    public String attribute(String name) {
        if (attributes == null || attributes.get(name) == null) {
            return null;
        }
        return attributes.get(name).stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }
}
