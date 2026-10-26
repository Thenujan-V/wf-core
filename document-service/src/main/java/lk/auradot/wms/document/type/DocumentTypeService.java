package lk.auradot.wms.document.type;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.Names;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.forms.FormClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentTypeService {

    private final DocumentTypeStore store;
    private final FormClient forms;
    private final Clock clock;

    public DocumentTypeService(DocumentTypeStore store, FormClient forms, Clock clock) {
        this.store = store;
        this.forms = forms;
        this.clock = clock;
    }

    public List<DocumentType> list() {
        return store.list();
    }

    public DocumentType create(TypeRequest request, Caller caller) {
        String name = checked(request, caller);
        String id = UUID.randomUUID().toString();
        try {
            store.insert(id, name, request.form(), clock.instant());
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        return store.find(id).orElseThrow();
    }

    public DocumentType update(String id, TypeRequest request, Caller caller) {
        String name = checked(request, caller);
        try {
            if (!store.update(id, name, request.form())) {
                throw ApiException.notFound("DOC_TYPE_NOT_FOUND", "No such document type");
            }
        } catch (DuplicateKeyException e) {
            throw nameTaken();
        }
        return store.find(id).orElseThrow();
    }

    public void delete(String id) {
        if (store.inUse(id)) {
            throw ApiException.conflict("DOC_TYPE_IN_USE", "Documents still use this type");
        }
        store.delete(id);
    }

    private String checked(TypeRequest request, Caller caller) {
        String name = Names.check(request.name());
        if (request.form() != null && !forms.isPublished(request.form(), caller.token())) {
            throw ApiException.badRequest("DOC_TYPE_FORM_INVALID", "Choose a published form version for the metadata");
        }
        return name;
    }

    private static ApiException nameTaken() {
        return ApiException.conflict("DOC_NAME_TAKEN", "A document type with this name already exists");
    }
}
