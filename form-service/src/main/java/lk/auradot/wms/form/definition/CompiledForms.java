package lk.auradot.wms.form.definition;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.form.model.FormDefinition;
import lk.auradot.wms.form.model.FormJson;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Published versions never change, so their parsed form is cached for the life of the service. */
@Component
public class CompiledForms {

    private final FormStore store;
    private final Map<String, FormDefinition> cache = new ConcurrentHashMap<>();

    public CompiledForms(FormStore store) {
        this.store = store;
    }

    public FormDefinition get(String key, int version) {
        FormDefinition cached = cache.get(key + "@" + version);
        if (cached != null) {
            return cached;
        }
        StoredForm stored = store.version(key, version).orElseThrow(() -> ApiException.notFound("FORM_VERSION_NOT_FOUND",
                "Form '" + key + "' has no version " + version));
        FormJson.Result result = FormJson.read(stored.definition());
        if (!result.ok()) {
            throw new IllegalStateException("Published form " + key + "@" + version + " no longer parses: " + result.errors());
        }
        cache.put(key + "@" + version, result.definition());
        return result.definition();
    }
}
