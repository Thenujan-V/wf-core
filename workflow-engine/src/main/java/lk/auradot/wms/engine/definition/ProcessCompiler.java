package lk.auradot.wms.engine.definition;

import java.util.ArrayList;
import java.util.List;

public final class ProcessCompiler {

    private ProcessCompiler() {
    }

    public static CompiledProcess compile(ProcessDefinition definition) {
        List<ValidationError> errors = DefinitionValidator.validate(definition);
        if (!errors.isEmpty()) {
            throw new InvalidDefinitionException(errors);
        }
        Graph graph = Graph.build(definition, new ArrayList<>());
        return new CompiledProcess(definition, graph, ParallelBlocks.analyze(graph, new ArrayList<>()));
    }
}
