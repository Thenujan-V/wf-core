package lk.auradot.wms.workflow.job;

/** Outcome of an external action, reported back to the engine. */
public record ActionResult(boolean success, String error, boolean retryable) {

    public static ActionResult ok() {
        return new ActionResult(true, null, false);
    }

    public static ActionResult failed(String error, boolean retryable) {
        return new ActionResult(false, error, retryable);
    }
}
