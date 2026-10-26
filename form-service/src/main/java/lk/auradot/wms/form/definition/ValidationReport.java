package lk.auradot.wms.form.definition;

import lk.auradot.wms.form.model.FieldError;

import java.util.List;

public record ValidationReport(boolean valid, List<FieldError> errors) {
}
