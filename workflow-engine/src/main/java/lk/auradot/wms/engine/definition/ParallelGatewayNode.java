package lk.auradot.wms.engine.definition;

/** Splits into one branch per outgoing flow, or joins by waiting for every branch of its split. */
public record ParallelGatewayNode(String id, String name) implements Node {
}
