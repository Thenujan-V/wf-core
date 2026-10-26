package lk.auradot.wms.workflow.definition;

import lk.auradot.wms.engine.definition.ValidationError;

import java.util.List;

public record ValidationReport(boolean valid, List<ValidationError> errors) {
}
