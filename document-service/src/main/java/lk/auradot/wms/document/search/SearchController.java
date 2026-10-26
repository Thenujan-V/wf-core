package lk.auradot.wms.document.search;

import lk.auradot.wms.document.access.Callers;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {

    private final SearchService search;
    private final Callers callers;

    public SearchController(SearchService search, Callers callers) {
        this.search = search;
        this.callers = callers;
    }

    @GetMapping("/search")
    public SearchResults search(@RequestParam(required = false) String q, @RequestParam(required = false) String folderId,
                                @RequestParam(required = false) String typeId, @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size, @AuthenticationPrincipal Jwt jwt) {
        return search.search(q, folderId, typeId, page, size, callers.of(jwt));
    }
}
