package lk.auradot.wms.document;

import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.document.access.Caller;
import lk.auradot.wms.document.requests.InstanceAccess;
import lk.auradot.wms.document.requests.InstanceAccessClient;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stands in for workflow-service's access-check: who may see (and attach to) which request. Like the real client, a
 * plain {@link #access} keeps its first answer (a cache that has not expired yet); {@link #fresh} asks again.
 */
public class FakeInstances implements InstanceAccessClient {

    private final Map<String, InstanceAccess> grants = new ConcurrentHashMap<>();
    private final Map<String, Optional<InstanceAccess>> cached = new ConcurrentHashMap<>();
    private final Set<String> failing = ConcurrentHashMap.newKeySet();
    private final AtomicInteger callsInTransaction = new AtomicInteger();

    public void reset() {
        grants.clear();
        cached.clear();
        failing.clear();
        callsInTransaction.set(0);
    }

    public void grant(String userId, String instanceId, String processKey, boolean canAttach) {
        grants.put(userId + "|" + instanceId, new InstanceAccess(instanceId, processKey, "ACTIVE", true, canAttach));
    }

    /** The request is over: the person still sees it, but it takes no more documents. */
    public void end(String userId, String instanceId) {
        grants.computeIfPresent(userId + "|" + instanceId,
                (key, was) -> new InstanceAccess(instanceId, was.processKey(), "CANCELLED", true, false));
    }

    /** workflow-service cannot answer about this request. */
    public void fail(String instanceId) {
        failing.add(instanceId);
    }

    /** How often workflow-service was asked while a database transaction (and its row locks) was open. */
    public int callsInTransaction() {
        return callsInTransaction.get();
    }

    @Override
    public Optional<InstanceAccess> access(String instanceId, Caller caller) {
        ask(instanceId);
        return cached.computeIfAbsent(caller.userId() + "|" + instanceId, key -> Optional.ofNullable(grants.get(key)));
    }

    @Override
    public Optional<InstanceAccess> fresh(String instanceId, Caller caller) {
        ask(instanceId);
        String key = caller.userId() + "|" + instanceId;
        Optional<InstanceAccess> answer = Optional.ofNullable(grants.get(key));
        cached.put(key, answer);
        return answer;
    }

    private void ask(String instanceId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            callsInTransaction.incrementAndGet();
        }
        if (failing.contains(instanceId)) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DOC_WORKFLOW_UNAVAILABLE", "Requests cannot be checked right now");
        }
    }
}
