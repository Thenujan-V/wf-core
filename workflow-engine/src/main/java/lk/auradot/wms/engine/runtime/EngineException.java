package lk.auradot.wms.engine.runtime;

/** A command was not allowed; the caller must discard the (partially changed) state. */
public class EngineException extends RuntimeException {

    private final EngineError error;

    public EngineException(EngineError error, String message) {
        super(message);
        this.error = error;
    }

    public EngineError error() {
        return error;
    }
}
