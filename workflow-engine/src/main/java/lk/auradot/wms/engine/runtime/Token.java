package lk.auradot.wms.engine.runtime;

/** Where one path of execution currently is; {@code scopeId} is its innermost parallel fork, or null. */
public final class Token {

    private final String id;
    private String nodeId;
    private TokenStatus status;
    private final String scopeId;

    public Token(String id, String nodeId, TokenStatus status, String scopeId) {
        this.id = id;
        this.nodeId = nodeId;
        this.status = status;
        this.scopeId = scopeId;
    }

    public String id() {
        return id;
    }

    public String nodeId() {
        return nodeId;
    }

    public TokenStatus status() {
        return status;
    }

    public String scopeId() {
        return scopeId;
    }

    void moveTo(String nodeId) {
        this.nodeId = nodeId;
        this.status = TokenStatus.ACTIVE;
    }

    void waitHere() {
        status = TokenStatus.WAITING;
    }

    void end() {
        status = TokenStatus.ENDED;
    }
}
