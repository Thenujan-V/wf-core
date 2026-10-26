package lk.auradot.wms.form.submission;

import lk.auradot.wms.form.model.FieldError;

import java.util.List;
import java.util.Map;

public record ValidateResponse(boolean valid, List<FieldError> errors, Map<String, Object> data) {
}
