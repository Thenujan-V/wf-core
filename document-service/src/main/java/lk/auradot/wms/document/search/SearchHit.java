package lk.auradot.wms.document.search;

import lk.auradot.wms.document.document.DocumentSummary;

public record SearchHit(DocumentSummary document, String folderName) {
}
