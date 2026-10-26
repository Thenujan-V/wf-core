package lk.auradot.wms.document.forms;

import lk.auradot.wms.common.error.ApiException;

import java.util.List;
import java.util.Map;

/** form-service, called with the caller's own token (spec §6: metadata is validated by the form rules). */
public interface FormClient {

    /** The cleaned values when valid; otherwise a 400 DOC_METADATA_INVALID carrying form-service's field errors. */
    Map<String, Object> validate(FormRef form, Map<String, Object> data, String token);

    /** Whether that form version exists and is published (not retired). */
    boolean isPublished(FormRef form, String token);

    static ApiException invalid(List<?> errors) {
        return ApiException.badRequest("DOC_METADATA_INVALID", "Some metadata fields need attention").with("errors", errors);
    }
}
