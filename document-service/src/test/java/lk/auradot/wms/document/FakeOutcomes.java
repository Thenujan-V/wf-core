package lk.auradot.wms.document;

import lk.auradot.wms.document.approval.ApprovalUnavailableException;
import lk.auradot.wms.document.approval.RequestOutcome;
import lk.auradot.wms.document.approval.RequestOutcomes;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stands in for workflow-service's outcome endpoint: what a test says about a request is what it answers. */
public class FakeOutcomes implements RequestOutcomes {

    private final Map<String, RequestOutcome> known = new ConcurrentHashMap<>();
    private volatile boolean failing;
    private final java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();

    public void reset() {
        known.clear();
        failing = false;
    }

    public void put(RequestOutcome outcome) {
        known.put(outcome.id(), outcome);
    }

    /** How often it was asked, answered or not. */
    public int asked() {
        return asked.get();
    }

    /** workflow-service cannot answer. */
    public void fail() {
        failing = true;
    }

    public void recover() {
        failing = false;
    }

    @Override
    public Optional<RequestOutcome> outcome(String instanceId) {
        asked.incrementAndGet();
        if (failing) {
            throw new ApprovalUnavailableException("workflow-service is down (test)", null);
        }
        return Optional.ofNullable(known.get(instanceId));
    }
}
