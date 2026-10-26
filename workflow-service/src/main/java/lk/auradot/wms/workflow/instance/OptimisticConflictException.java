package lk.auradot.wms.workflow.instance;

/** Someone else saved the instance after we loaded it; the command must be retried on fresh state. */
public class OptimisticConflictException extends RuntimeException {

    public OptimisticConflictException(String instanceId) {
        super("Instance " + instanceId + " was changed concurrently");
    }
}
