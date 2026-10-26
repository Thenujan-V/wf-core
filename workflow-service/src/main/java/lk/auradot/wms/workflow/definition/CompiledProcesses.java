package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.engine.definition.CompiledProcess;
import lk.auradot.wms.engine.definition.ProcessCompiler;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Published versions never change, so their compiled form is cached for the life of the service. */
@Component
public class CompiledProcesses {

    private final DefinitionStore store;
    private final Map<String, CompiledProcess> cache = new ConcurrentHashMap<>();

    public CompiledProcesses(DefinitionStore store) {
        this.store = store;
    }

    public CompiledProcess get(String definitionId) {
        return cache.computeIfAbsent(definitionId, id -> {
            StoredDefinition stored = store.byId(id)
                    .orElseThrow(() -> new IllegalStateException("Unknown process definition " + id));
            DefinitionJson.Result result = DefinitionJson.read(stored.key(), stored.version(), stored.name(), stored.graph());
            if (!result.ok()) {
                throw new IllegalStateException("Published definition " + id + " no longer parses: " + result.errors());
            }
            return ProcessCompiler.compile(result.definition());
        });
    }
}
