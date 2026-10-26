package lk.auradot.wms.document.search;

import java.util.List;

public record SearchPage(List<String> documentIds, long total) {
}
