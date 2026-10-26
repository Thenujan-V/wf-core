package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.workflow.forms.CatalogItem;
import lk.auradot.wms.workflow.forms.FormRefView;
import lk.auradot.wms.workflow.forms.NodeForms;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Processes any signed-in user can start, with the start form each one needs. */
@RestController
public class CatalogController {

    private final DefinitionStore store;
    private final CompiledProcesses processes;
    private final NodeForms nodeForms;

    public CatalogController(DefinitionStore store, CompiledProcesses processes, NodeForms nodeForms) {
        this.store = store;
        this.processes = processes;
        this.nodeForms = nodeForms;
    }

    @GetMapping("/catalog")
    public List<CatalogItem> catalog() {
        return store.catalog().stream().map(entry -> {
            String id = store.version(entry.key(), entry.version()).orElseThrow().id();
            String startNode = processes.get(id).start().id();
            return new CatalogItem(entry.key(), entry.name(), entry.version(), FormRefView.of(nodeForms.of(id).get(startNode)));
        }).toList();
    }
}
