package lk.auradot.wms.workflow.directory;

/** identity-service could not be reached or answered with an error other than "not found". */
public class DirectoryUnavailableException extends RuntimeException {

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
