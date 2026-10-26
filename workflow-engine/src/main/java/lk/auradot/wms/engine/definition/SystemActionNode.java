package lk.auradot.wms.engine.definition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** SEND_EMAIL, SET_VARIABLE or WEBHOOK; always executed as an asynchronous job. */
public record SystemActionNode(String id, String name, ActionType type, Map<String, Object> config) implements Node {
    public SystemActionNode {
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
    }
}
