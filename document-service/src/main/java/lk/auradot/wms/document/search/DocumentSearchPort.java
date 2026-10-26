package lk.auradot.wms.document.search;

import java.util.Set;

/** Spec §6: search behind a port, so an OpenSearch adapter can replace MySQL FULLTEXT later. */
public interface DocumentSearchPort {

    /** Documents in these folders matching the query, best first. */
    SearchPage search(SearchQuery query, Set<String> folderIds);
}
