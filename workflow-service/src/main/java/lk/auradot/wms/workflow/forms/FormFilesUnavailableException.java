package lk.auradot.wms.workflow.forms;

/**
 * form-service is fine but could not check the submission's files with document-service (FORM_FILES_UNAVAILABLE).
 * Not a sign that form-service is unhealthy: forms without files must keep working.
 */
public class FormFilesUnavailableException extends RuntimeException {

    public FormFilesUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
