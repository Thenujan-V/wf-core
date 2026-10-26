package lk.auradot.wms.engine.definition;

/** {@code endStatus} (e.g. APPROVED) becomes the instance's end status; may be null. */
public record EndNode(String id, String name, String endStatus) implements Node {
}
