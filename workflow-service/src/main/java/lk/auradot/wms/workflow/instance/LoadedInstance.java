package lk.auradot.wms.workflow.instance;

import lk.auradot.wms.engine.runtime.InstanceState;

/** An instance as loaded, with the version its optimistic update must match. */
public record LoadedInstance(String definitionId, int version, InstanceState state) {
}
