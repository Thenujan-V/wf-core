package lk.auradot.wms.engine.definition;

/** Takes the first outgoing flow whose condition is true, otherwise the default flow. */
public record ExclusiveGatewayNode(String id, String name) implements Node {
}
