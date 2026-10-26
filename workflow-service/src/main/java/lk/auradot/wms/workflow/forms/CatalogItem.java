package lk.auradot.wms.workflow.forms;

/** A process people can start, and the form they fill in to start it (null: free details). */
public record CatalogItem(String key, String name, int version, FormRefView startForm) {
}
