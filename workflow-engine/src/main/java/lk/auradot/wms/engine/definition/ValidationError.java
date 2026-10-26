package lk.auradot.wms.engine.definition;

/** A publish-blocking problem; {@code nodeId} lets the designer highlight the node (null = whole process). */
public record ValidationError(String nodeId, String code, String message) {
}
