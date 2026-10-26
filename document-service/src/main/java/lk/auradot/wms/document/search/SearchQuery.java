package lk.auradot.wms.document.search;

public record SearchQuery(String booleanQuery, String typeId, int page, int size) {
}
