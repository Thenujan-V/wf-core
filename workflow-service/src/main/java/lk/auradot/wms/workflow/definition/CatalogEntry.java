package lk.auradot.wms.workflow.definition;

/** A process people can start: its latest published version. */
public record CatalogEntry(String key, String name, int version) {
}
