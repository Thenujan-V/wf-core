package lk.auradot.wms.form.files;

import lk.auradot.wms.form.model.Field;
import lk.auradot.wms.form.model.FieldError;
import lk.auradot.wms.form.model.FieldType;
import lk.auradot.wms.form.model.FormDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spec §2.2 "form → document (verify file references)": new references in file fields must be the submitter's staged
 * files. References the step already had (its baseline) are the request's own and are not checked again. Stored
 * names are document-service's, not whatever the browser sent.
 */
@Component
public class FileReferences {

    public record Result(Map<String, Object> data, List<FieldError> errors) {
    }

    private final FileVerifier verifier;

    public FileReferences(FileVerifier verifier) {
        this.verifier = verifier;
    }

    /** {@code data} is already valid (SubmissionValidator); throws {@link FilesUnavailableException}. */
    @SuppressWarnings("unchecked")
    public Result check(FormDefinition form, Map<String, Object> data, Map<String, ?> baseline, String submittedBy) {
        Map<Field, List<Map<String, Object>>> refsByField = new LinkedHashMap<>();
        Set<String> toVerify = new LinkedHashSet<>();
        for (Field field : form.fields()) {
            if (field.type() != FieldType.FILE || !(data.get(field.key()) instanceof List<?> items)) {
                continue;
            }
            Map<Object, Object> known = namesById(baseline.get(field.key()));
            List<Map<String, Object>> refs = new ArrayList<>();
            for (Object item : items) {
                Map<String, Object> ref = new LinkedHashMap<>((Map<String, Object>) item);
                refs.add(ref);
                if (known.containsKey(ref.get("documentId"))) {
                    ref.put("name", known.get(ref.get("documentId"))); // the request's file keeps its recorded name
                } else {
                    toVerify.add((String) ref.get("documentId"));
                }
            }
            refsByField.put(field, refs);
        }
        Map<String, Object> clean = new LinkedHashMap<>(data);
        if (toVerify.isEmpty()) {
            refsByField.forEach((field, refs) -> clean.put(field.key(), refs));
            return new Result(clean, List.of());
        }
        Map<String, VerifiedFile> verified = verifier.verify(submittedBy, List.copyOf(toVerify)).files().stream()
                .collect(Collectors.toMap(VerifiedFile::documentId, Function.identity(), (a, b) -> a));
        List<FieldError> errors = new ArrayList<>();
        refsByField.forEach((field, refs) -> {
            boolean missing = false;
            for (Map<String, Object> ref : refs) {
                String id = (String) ref.get("documentId");
                if (!toVerify.contains(id)) {
                    continue;
                }
                VerifiedFile file = verified.get(id);
                if (file == null) {
                    missing = true;
                } else {
                    ref.put("name", file.name());
                }
            }
            if (missing) {
                errors.add(new FieldError(field.key(), "FILE_NOT_AVAILABLE",
                        field.label() + " includes a file that is no longer available; remove it and upload it again"));
            }
            clean.put(field.key(), refs);
        });
        return new Result(errors.isEmpty() ? clean : Map.of(), List.copyOf(errors));
    }

    private static Map<Object, Object> namesById(Object value) {
        Map<Object, Object> names = new HashMap<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> ref) {
                    names.put(ref.get("documentId"), ref.get("name"));
                }
            }
        }
        return names;
    }
}
