package lk.auradot.wms.document.search;

import java.util.List;

public record SearchResults(List<SearchHit> items, long total, int page, int size) {
}
