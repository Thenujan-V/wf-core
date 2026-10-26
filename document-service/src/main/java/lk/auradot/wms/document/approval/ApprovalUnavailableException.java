package lk.auradot.wms.document.approval;

/** A service needed to settle an approval could not answer; nothing was decided, and it is tried again later. */
public class ApprovalUnavailableException extends RuntimeException {

    public ApprovalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
