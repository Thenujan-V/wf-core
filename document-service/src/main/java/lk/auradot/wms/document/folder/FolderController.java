package lk.auradot.wms.document.folder;

import jakarta.validation.Valid;
import lk.auradot.wms.document.access.Callers;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/folders")
public class FolderController {

    private final FolderService folders;
    private final Callers callers;

    public FolderController(FolderService folders, Callers callers) {
        this.folders = folders;
        this.callers = callers;
    }

    @GetMapping("/root")
    public FolderView root(@AuthenticationPrincipal Jwt jwt) {
        return folders.view(Folders.ROOT_ID, callers.of(jwt));
    }

    @GetMapping("/{id}")
    public FolderView get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return folders.view(id, callers.of(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FolderView create(@Valid @RequestBody CreateFolderRequest request, @AuthenticationPrincipal Jwt jwt) {
        return folders.create(request, callers.of(jwt));
    }

    @PatchMapping("/{id}")
    public FolderView rename(@PathVariable String id, @Valid @RequestBody RenameRequest request, @AuthenticationPrincipal Jwt jwt) {
        return folders.rename(id, request, callers.of(jwt));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        folders.delete(id, callers.of(jwt));
    }

    @GetMapping("/{id}/acl")
    public AclView acl(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        return folders.acl(id, callers.of(jwt));
    }

    @PutMapping("/{id}/acl")
    public AclView setAcl(@PathVariable String id, @Valid @RequestBody AclRequest request, @AuthenticationPrincipal Jwt jwt) {
        return folders.setAcl(id, request, callers.of(jwt));
    }
}
