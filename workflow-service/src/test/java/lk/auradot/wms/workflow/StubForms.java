package lk.auradot.wms.workflow;

import lk.auradot.wms.workflow.forms.FormClient;
import lk.auradot.wms.workflow.forms.FormServiceUnavailableException;
import lk.auradot.wms.workflow.forms.FormValidationException;
import lk.auradot.wms.workflow.forms.FormVersion;
import lk.auradot.wms.workflow.forms.SubmitForm;
import lk.auradot.wms.workflow.forms.Submitted;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory form-service: published versions, recorded submissions, and switches for failures. */
public class StubForms implements FormClient {

    private final Map<String, FormVersion> versions = new ConcurrentHashMap<>();
    private final List<SubmitForm> submissions = new CopyOnWriteArrayList<>();
    private volatile boolean failing;
    private volatile List<Map<String, Object>> reject;
    private volatile boolean filesUnavailable;

    public void reset() {
        versions.clear();
        submissions.clear();
        failing = false;
        reject = null;
        filesUnavailable = false;
    }

    public void publish(String key, int version, String status, Map<String, Object> definition) {
        versions.put(key + "@" + version, new FormVersion(key, version, status, definition));
    }

    public StubForms failing(boolean failing) {
        this.failing = failing;
        return this;
    }

    /** The next submission finds document-service unable to check its files (form-service's FORM_FILES_UNAVAILABLE). */
    public void filesUnavailableNext() {
        filesUnavailable = true;
    }

    public void rejectNext(List<Map<String, Object>> errors) {
        reject = errors;
    }

    public List<SubmitForm> submissions() {
        return List.copyOf(submissions);
    }

    @Override
    public Optional<FormVersion> version(String key, int version) {
        check();
        return Optional.ofNullable(versions.get(key + "@" + version));
    }

    @Override
    public Submitted submit(SubmitForm request) {
        check();
        if (filesUnavailable) {
            filesUnavailable = false;
            throw new lk.auradot.wms.workflow.forms.FormFilesUnavailableException("document-service is down (test)", null);
        }
        List<Map<String, Object>> errors = reject;
        if (errors != null) {
            reject = null;
            throw new FormValidationException(errors);
        }
        submissions.add(request);
        // Like form-service: fields that are not editable at this step keep their baseline value.
        Map<String, Object> data = new LinkedHashMap<>(request.data());
        request.access().forEach((key, level) -> {
            if (!"EDITABLE".equals(level)) {
                data.remove(key);
                if (request.baseline().containsKey(key)) {
                    data.put(key, request.baseline().get(key));
                }
            }
        });
        return new Submitted("sub-" + submissions.size(), data);
    }

    private void check() {
        if (failing) {
            throw new FormServiceUnavailableException("form-service is down (test)", null);
        }
    }
}
