package lk.auradot.wms.notification.plan;

import lk.auradot.wms.notification.directory.Directory;
import lk.auradot.wms.notification.directory.Person;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Spec §8: which events concern whom. Nobody is told about their own action. */
@Component
public class NotificationPlanner {

    public static final String EMAIL_REQUESTED = "workflow.email.requested";

    /** Every event this service listens to. */
    public static final List<String> ROUTING_KEYS = List.of(
            "workflow.task.created", "workflow.task.delegated", "workflow.task.reassigned", "workflow.task.reminder",
            "workflow.task.overdue", "workflow.instance.completed", "workflow.instance.cancelled",
            "document.folder.shared", EMAIL_REQUESTED);

    private final Directory directory;

    public NotificationPlanner(Directory directory) {
        this.directory = directory;
    }

    public List<Planned> plan(String routingKey, Map<String, Object> event) {
        Map<?, ?> data = event.get("data") instanceof Map<?, ?> map ? map : Map.of();
        List<Planned> planned = switch (routingKey == null ? "" : routingKey) {
            case "workflow.task.created" -> taskCreated(event, data);
            case "workflow.task.delegated", "workflow.task.reassigned" ->
                    one(text(data.get("toUserId")), Kind.TASK_ASSIGNED, taskValues(event), "/inbox");
            case "workflow.task.reminder" -> one(text(data.get("assigneeId")), Kind.TASK_REMINDER, taskValues(event), "/inbox");
            case "workflow.task.overdue" -> one(text(data.get("assigneeId")), Kind.TASK_OVERDUE, taskValues(event), "/inbox");
            case "workflow.instance.completed" -> finished(event, outcome(text(data.get("endStatus"))));
            case "workflow.instance.cancelled" -> finished(event, "cancelled");
            case "document.folder.shared" -> shared(event);
            default -> List.of();
        };
        String actor = text(event.get("actorId"));
        return planned.stream().filter(p -> !p.userId().equals(actor)).toList();
    }

    /** The email a "send email" step asked for; empty for other events, or when its address cannot be worked out. */
    public Optional<ProcessEmail> processEmail(String routingKey, Map<String, Object> event) {
        if (!EMAIL_REQUESTED.equals(routingKey) || !(event.get("email") instanceof Map<?, ?> email)) {
            return Optional.empty();
        }
        String to = text(email.get("to"));
        String template = text(email.get("template"));
        Map<?, ?> variables = event.get("variables") instanceof Map<?, ?> map ? map : Map.of();
        if (to != null && !to.contains("@")) {
            // A path into the request's data, e.g. form.email or initiator; ${initiator} means the same.
            String path = to.startsWith("${") && to.endsWith("}") ? to.substring(2, to.length() - 1).strip() : to;
            to = text(resolve(variables, path));
        }
        if (to == null || template == null) {
            return Optional.empty();
        }
        Map<String, String> values = requestValues(event);
        if (variables.get("form") instanceof Map<?, ?> form) {
            form.forEach((key, value) -> {
                if (value != null && !(value instanceof Map) && !(value instanceof List)) {
                    values.put("form." + key, String.valueOf(value));
                }
            });
        }
        return Optional.of(new ProcessEmail(to, template, values));
    }

    private List<Planned> taskCreated(Map<String, Object> event, Map<?, ?> data) {
        String assignee = text(data.get("assigneeId"));
        if (assignee != null) {
            return one(assignee, Kind.TASK_ASSIGNED, taskValues(event), "/inbox");
        }
        // Offered to pools: every enabled member, once, however many of the pools they are in.
        Map<String, Planned> members = new LinkedHashMap<>();
        if (data.get("candidates") instanceof List<?> candidates) {
            for (Object candidate : candidates) {
                for (Person person : members(String.valueOf(candidate))) {
                    if (person.enabled()) {
                        members.putIfAbsent(person.id(), new Planned(person.id(), Kind.TASK_AVAILABLE, taskValues(event), "/inbox"));
                    }
                }
            }
        }
        return List.copyOf(members.values());
    }

    private List<Person> members(String candidate) {
        if (candidate.startsWith("group:")) {
            return directory.groupMembers(candidate.substring("group:".length()));
        }
        if (candidate.startsWith("role:")) {
            return directory.roleMembers(candidate.substring("role:".length()));
        }
        return List.of();
    }

    private List<Planned> finished(Map<String, Object> event, String outcome) {
        Map<String, String> values = requestValues(event);
        values.put("outcome", outcome);
        return one(text(event.get("initiatorId")), Kind.REQUEST_FINISHED, values, "/requests/" + text(event.get("instanceId")));
    }

    private List<Planned> shared(Map<String, Object> event) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("folderName", String.valueOf(event.get("folderName")));
        values.put("permission", String.valueOf(event.get("permission")).toLowerCase(Locale.ROOT));
        return one(text(event.get("userId")), Kind.DOCUMENT_SHARED, values, "/documents/folders/" + text(event.get("folderId")));
    }

    private static List<Planned> one(String userId, Kind kind, Map<String, String> values, String link) {
        return userId == null ? List.of() : List.of(new Planned(userId, kind, Map.copyOf(values), link));
    }

    private static Map<String, String> taskValues(Map<String, Object> event) {
        Map<String, String> values = requestValues(event);
        String taskName = text(event.get("taskName"));
        values.put("taskName", taskName == null ? "a task" : taskName);
        return values;
    }

    /** {@code reference} is what people call the request: its business key, or the process when it has none. */
    private static Map<String, String> requestValues(Map<String, Object> event) {
        Map<String, String> values = new LinkedHashMap<>();
        String processKey = text(event.get("processKey"));
        String businessKey = text(event.get("businessKey"));
        values.put("processKey", processKey == null ? "" : processKey);
        values.put("reference", businessKey != null ? businessKey : processKey == null ? "" : processKey);
        return values;
    }

    /** "NEEDS_REWORK" → "needs rework"; a request that simply ended is "completed". */
    private static String outcome(String endStatus) {
        return endStatus == null ? "completed" : endStatus.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static Object resolve(Map<?, ?> variables, String path) {
        Object current = variables;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
