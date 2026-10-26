package lk.auradot.wms.document;

import lk.auradot.wms.document.approval.ApprovalUnavailableException;
import lk.auradot.wms.document.approval.People;

/** Everyone is called "Name of <id>", so a test can tell a name from an id. */
public class FakePeople implements People {

    private volatile boolean failing;

    public void reset() {
        failing = false;
    }

    public void fail() {
        failing = true;
    }

    public void recover() {
        failing = false;
    }

    @Override
    public String displayName(String userId) {
        if (failing) {
            throw new ApprovalUnavailableException("identity-service is down (test)", null);
        }
        return "Name of " + userId;
    }
}
