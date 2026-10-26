package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.workflow.forms.FormRef;
import lk.auradot.wms.workflow.forms.NodeForms;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec §5 per-task field permissions, applied to reading a request: a field is left out for someone whose every step
 * marks it HIDDEN. The initiator (who filled it in), admins, and people in any step that does not hide it keep it.
 */
@Component
public class VariableVisibility {

    private final NodeForms nodeForms;
    private final WorkflowQueries queries;

    public VariableVisibility(NodeForms nodeForms, WorkflowQueries queries) {
        this.nodeForms = nodeForms;
        this.queries = queries;
    }

    public Map<String, Object> forViewer(InstanceDetail detail, String definitionId, String userId, boolean admin,
                                         List<String> pools) {
        Set<String> hidden = hiddenFor(detail.instance().id(), definitionId, detail.instance().initiatorId(), userId,
                admin, pools);
        if (hidden.isEmpty() || !(detail.variables().get("form") instanceof Map<?, ?> form)) {
            return detail.variables();
        }
        Map<String, Object> visibleForm = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : form.entrySet()) {
            if (!hidden.contains(String.valueOf(entry.getKey()))) {
                visibleForm.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        Map<String, Object> variables = new LinkedHashMap<>(detail.variables());
        variables.put("form", visibleForm);
        return variables;
    }

    /** The form fields this reader may not see anywhere in the request: HIDDEN in every step they take part in. */
    public Set<String> hiddenFor(String instanceId, String definitionId, String initiatorId, String userId, boolean admin,
                                 List<String> pools) {
        if (admin || userId.equals(initiatorId)) {
            return Set.of();
        }
        Set<String> hidden = null;
        Map<String, FormRef> forms = nodeForms.of(definitionId);
        for (String node : queries.involvedNodes(instanceId, userId, pools)) {
            FormRef ref = forms.get(node);
            Set<String> hiddenHere = ref == null ? Set.of() : ref.access().entrySet().stream()
                    .filter(e -> "HIDDEN".equals(e.getValue())).map(Map.Entry::getKey).collect(Collectors.toSet());
            if (hidden == null) {
                hidden = new HashSet<>(hiddenHere);
            } else {
                hidden.retainAll(hiddenHere);
            }
        }
        return hidden == null ? Set.of() : hidden;
    }
}
