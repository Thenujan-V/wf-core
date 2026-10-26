package lk.auradot.wms.engine.definition;

import java.util.List;

public class InvalidDefinitionException extends RuntimeException {

    private final List<ValidationError> errors;

    public InvalidDefinitionException(List<ValidationError> errors) {
        super("Process definition is invalid: " + errors);
        this.errors = List.copyOf(errors);
    }

    public List<ValidationError> errors() {
        return errors;
    }
}
