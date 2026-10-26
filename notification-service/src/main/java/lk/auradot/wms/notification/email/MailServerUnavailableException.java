package lk.auradot.wms.notification.email;

/**
 * The mail server could not be reached, or did not let us in: nothing is wrong with the email itself. It waits and is
 * tried again without the failure counting against it.
 */
public class MailServerUnavailableException extends RuntimeException {

    public MailServerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
