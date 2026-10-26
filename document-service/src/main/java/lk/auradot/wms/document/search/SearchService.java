package lk.auradot.wms.document.search;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.access.Access;
import lk.auradot.wms.document.access.AclSnapshot;
import lk.auradot.wms.document.access.AclSnapshots;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.access.FolderNode;
import lk.auradot.wms.document.access.Permission;
import lk.auradot.wms.document.document.DocumentStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class SearchService {

    private final DocumentSearchPort port;
    private final DocumentStore documents;
    private final AclSnapshots snapshots;
    private final Access access;

    public SearchService(DocumentSearchPort port, DocumentStore documents, AclSnapshots snapshots, Access access) {
        this.port = port;
        this.documents = documents;
        this.snapshots = snapshots;
        this.access = access;
    }

    /** Only documents in folders the caller may read (spec §6), optionally below one folder. */
    public SearchResults search(String text, String folderId, String typeId, int page, int size, Caller caller) {
        String query = SearchTerms.booleanQuery(text).orElseThrow(() -> ApiException.badRequest("DOC_SEARCH_QUERY",
                "Type at least two letters or digits to search"));
        if (page < 0 || size < 1 || size > 100) {
            throw ApiException.badRequest("DOC_SEARCH_PAGE", "Pages start at 0 and hold 1 to 100 results");
        }
        AclSnapshot acl = snapshots.current();
        Set<String> folders = acl.readable(caller);
        if (folderId != null) {
            access.folder(folderId, caller, Permission.READ);
            folders.retainAll(acl.subtree(folderId));
        }
        SearchPage found = port.search(new SearchQuery(query, typeId, page, size), folders);
        List<SearchHit> items = documents.summaries(found.documentIds()).stream()
                .map(d -> new SearchHit(d, acl.folder(d.folderId()).map(FolderNode::name).orElse(null)))
                .toList();
        return new SearchResults(items, found.total(), page, size);
    }
}
