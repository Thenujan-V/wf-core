package lk.auradot.wms.workflow.forms;

/**
 * form-service answered with a client error other than a validation failure: a bug in how workflow-service calls it,
 * not an outage, so it neither reads as "try again later" nor counts towards opening the circuit.
 */
public class FormServiceRejectedException extends RuntimeException {

    public FormServiceRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
