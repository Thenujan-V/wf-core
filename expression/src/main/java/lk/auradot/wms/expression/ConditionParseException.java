package lk.auradot.wms.expression;

/** A condition's JSON is malformed; {@link #path()} points at the offending part, e.g. {@code $.all[0].op}. */
public class ConditionParseException extends RuntimeException {

    private final String path;

    public ConditionParseException(String path, String message) {
        super(path + ": " + message);
        this.path = path;
    }

    public String path() {
        return path;
    }
}
