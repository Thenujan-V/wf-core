package lk.auradot.wms.document.document;

import java.util.Map;

/** Replaces the document's type and metadata together; a null type clears both. */
public record MetadataRequest(String typeId, Map<String, Object> metadata) {
}
