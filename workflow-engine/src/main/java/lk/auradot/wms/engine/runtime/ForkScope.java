package lk.auradot.wms.engine.runtime;

/** One execution of a parallel split: how many branches its join still waits for. */
public final class ForkScope {

    private final String id;
    private final String parentScopeId;
    private final String splitNodeId;
    private final String joinNodeId;
    private final int expected;
    private int arrived;

    public ForkScope(String id, String parentScopeId, String splitNodeId, String joinNodeId, int expected, int arrived) {
        this.id = id;
        this.parentScopeId = parentScopeId;
        this.splitNodeId = splitNodeId;
        this.joinNodeId = joinNodeId;
        this.expected = expected;
        this.arrived = arrived;
    }

    public String id() {
        return id;
    }

    public String parentScopeId() {
        return parentScopeId;
    }

    public String splitNodeId() {
        return splitNodeId;
    }

    public String joinNodeId() {
        return joinNodeId;
    }

    public int expected() {
        return expected;
    }

    public int arrived() {
        return arrived;
    }

    void arrive() {
        arrived++;
    }

    boolean complete() {
        return arrived >= expected;
    }
}
