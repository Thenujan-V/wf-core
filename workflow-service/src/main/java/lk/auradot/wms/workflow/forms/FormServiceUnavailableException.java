package lk.auradot.wms.workflow.forms;

/** form-service could not be reached or failed; the caller persists nothing and answers 503. */
public class FormServiceUnavailableException extends RuntimeException {

    public FormServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
