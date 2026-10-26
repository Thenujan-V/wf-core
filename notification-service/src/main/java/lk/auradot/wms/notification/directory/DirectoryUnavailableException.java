package lk.auradot.wms.notification.directory;

/** identity-service could not answer; the event is retried rather than planned without its recipients. */
public class DirectoryUnavailableException extends RuntimeException {

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
