package lk.auradot.wms.notification.email;

import java.time.Instant;

/** An email taken from the queue. {@code claimedUntil} shows whose hold it is: only the holder may send or mark it. */
public record QueuedEmail(long id, String to, String subject, String body, int attempts, Instant claimedUntil) {
}
