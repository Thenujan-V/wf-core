package lk.auradot.wms.engine.runtime;

import java.time.Duration;

/** Total attempts for a system action, and the exponential backoff between them. */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, int multiplier) {

    public static RetryPolicy defaults() {
        return new RetryPolicy(5, Duration.ofMinutes(1), 5);
    }

    /** Delay after the given (1-based) failed attempt. */
    public Duration backoffAfter(int attempt) {
        return initialBackoff.multipliedBy((long) Math.pow(multiplier, Math.max(0, attempt - 1)));
    }
}
