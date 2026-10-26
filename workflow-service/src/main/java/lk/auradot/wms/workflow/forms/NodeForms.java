package lk.auradot.wms.workflow.forms;

import lk.auradot.wms.workflow.definition.DefinitionStore;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The forms of each published process version; published versions never change, so they are cached. */
@Component
public class NodeForms {

    private final DefinitionStore definitions;
    private final Map<String, Map<String, FormRef>> cache = new ConcurrentHashMap<>();

    public NodeForms(DefinitionStore definitions) {
        this.definitions = definitions;
    }

    public Map<String, FormRef> of(String definitionId) {
        return cache.computeIfAbsent(definitionId, id -> ProcessForms.read(definitions.byId(id)
                .orElseThrow(() -> new IllegalStateException("Unknown process definition " + id)).graph()).forms());
    }
}
