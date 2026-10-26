package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.common.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spec §5: when a start node or task has a form, form-service validates and stores what the person submitted and
 * returns the cleaned values the engine merges. This runs before the engine transaction; if that transaction then
 * fails, the stored submission is a harmless orphan.
 */
@Component
public class FormSubmissions {

    /** Start-data keys with this prefix pass through without a form field. */
    public static final String INTEGRATION_PREFIX = "auradocs";

    private final FormClient forms;
    private final NodeForms nodeForms;
    private final Map<String, Set<String>> fieldsByVersion = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fileFieldsByVersion = new ConcurrentHashMap<>();

    public FormSubmissions(FormClient forms, NodeForms nodeForms) {
        this.forms = forms;
        this.nodeForms = nodeForms;
    }

    public Map<String, Object> acceptStart(String definitionId, String startNodeId, String processKey,
                                           Map<String, Object> data, String userId) {
        FormRef ref = nodeForms.of(definitionId).get(startNodeId);
        if (ref == null) {
            return data;
        }
        // Context from an integrating app (auraDOCS: auradocsDocId, ...) is kept although the form does not declare it;
        // form-service would reject it as an unknown field.
        Map<String, Object> context = new LinkedHashMap<>();
        Map<String, Object> formData = new LinkedHashMap<>(data);
        if (data.keySet().stream().anyMatch(k -> k.startsWith(INTEGRATION_PREFIX))) {
            Set<String> declared = inputFields(ref);
            data.forEach((key, value) -> {
                if (key.startsWith(INTEGRATION_PREFIX) && !declared.contains(key)) {
                    context.put(key, value);
                    formData.remove(key);
                }
            });
        }
        Map<String, Object> clean = submit(
                new SubmitForm(ref.key(), ref.version(), formData, Map.of(), Map.of(), userId, "start:" + processKey));
        if (context.isEmpty()) {
            return clean;
        }
        Map<String, Object> merged = new LinkedHashMap<>(clean);
        merged.putAll(context);
        return merged;
    }

    public Map<String, Object> acceptTask(String definitionId, String nodeId, String taskId, Map<String, Object> data,
                                          Map<String, Object> currentForm, String userId) {
        Map<String, FormRef> forms = nodeForms.of(definitionId);
        FormRef ref = forms.get(nodeId);
        if (ref == null) {
            // In a process that uses forms, only forms may change the request's values (spec §5).
            if (!data.isEmpty() && !forms.isEmpty()) {
                throw ApiException.badRequest("WF_TASK_HAS_NO_FORM", "This step has no form; send only an outcome and a comment");
            }
            return data;
        }
        Map<String, Object> clean =
                submit(new SubmitForm(ref.key(), ref.version(), data, ref.access(), currentForm, userId, "task:" + taskId));
        Map<String, Object> merged = new LinkedHashMap<>(clean);
        // Values not editable at this step are unchanged by construction; writing them back could undo a parallel step.
        ref.access().forEach((key, level) -> {
            if (!"EDITABLE".equals(level)) {
                merged.remove(key);
            }
        });
        // An editable field the person cleared, or hid through a rule, must not keep its old value.
        for (String key : inputFields(ref)) {
            if ("EDITABLE".equals(ref.access().getOrDefault(key, "EDITABLE")) && !clean.containsKey(key)) {
                merged.put(key, null);
            }
        }
        return merged;
    }

    /** Input field keys of a form version; versions never change, so they are cached. */
    private Set<String> inputFields(FormRef ref) {
        return fieldsByVersion.computeIfAbsent(ref.key() + "@" + ref.version(),
                id -> Set.copyOf(FormReferenceChecks.inputFields(definition(ref)).keySet()));
    }

    /**
     * Spec §5 file fields: the document ids in a submitted step's file fields. document-service attaches the ones that
     * are still staged by the submitter; ids the request already has are ignored there.
     */
    public List<String> submittedFiles(String definitionId, String nodeId, Map<String, Object> values) {
        FormRef ref = nodeForms.of(definitionId).get(nodeId);
        if (ref == null) {
            return List.of();
        }
        Set<String> fileFields = fileFieldsByVersion.computeIfAbsent(ref.key() + "@" + ref.version(),
                id -> Set.copyOf(FormReferenceChecks.fileFields(definition(ref))));
        List<String> ids = new ArrayList<>();
        for (String key : fileFields) {
            if (values.get(key) instanceof List<?> refs) {
                for (Object item : refs) {
                    if (item instanceof Map<?, ?> file && file.get("documentId") instanceof String id && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private Map<String, Object> definition(FormRef ref) {
        FormVersion version = call(() -> forms.version(ref.key(), ref.version())).orElseThrow(
                () -> new IllegalStateException("Published process refers to missing form " + ref.key() + "@" + ref.version()));
        return version.definition();
    }

    private Map<String, Object> submit(SubmitForm request) {
        try {
            return call(() -> forms.submit(request).data());
        } catch (FormValidationException e) {
            throw ApiException.badRequest("FORM_VALIDATION_FAILED", "Some fields need attention").with("errors", e.errors());
        } catch (FormFilesUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WF_FILES_UNAVAILABLE",
                    "Attached files cannot be checked right now; please try again shortly");
        }
    }

    private static <T> T call(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (FormServiceUnavailableException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WF_FORM_SERVICE_UNAVAILABLE",
                    "Forms are unavailable right now; please try again shortly");
        }
    }
}
