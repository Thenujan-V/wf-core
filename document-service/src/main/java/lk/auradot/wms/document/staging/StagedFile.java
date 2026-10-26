package lk.auradot.wms.document.staging;

/** A staged file as form-service needs it: the canonical name, not the one the browser sent. */
public record StagedFile(String documentId, String name, long size, String mimeType) {
}
