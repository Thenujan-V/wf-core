package lk.auradot.wms.document.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Where file bytes live. The browser moves files through presigned URLs; the service checks and reads them, and stores
 * only what it makes itself.
 */
public interface ObjectStore {

    /** A PUT URL that accepts exactly this size and content type. */
    URI presignUpload(String key, long size, String contentType, Duration ttl);

    /** Stores bytes the service made itself (a rendered PDF); browsers never send files this way. */
    void put(String key, byte[] bytes, String contentType);

    Optional<Long> size(String key);

    InputStream open(String key);

    /** Copies inside the store; the source must exist. */
    void copy(String fromKey, String toKey);

    /** Deleting a missing object is not an error. */
    void delete(String key);

    URI presignDownload(String key, String fileName, String contentType, boolean inline, Duration ttl);

    class MissingObjectException extends RuntimeException {
        public MissingObjectException(String key) {
            super("No object " + key);
        }
    }
}
