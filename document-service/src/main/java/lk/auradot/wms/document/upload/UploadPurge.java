package lk.auradot.wms.document.upload;

import lk.auradot.wms.document.storage.ObjectStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/** Removes uploads that were never confirmed within the window, with their objects; confirmed rows are just trimmed. */
@Component
public class UploadPurge {

    private static final Logger log = LoggerFactory.getLogger(UploadPurge.class);

    private final UploadStore uploads;
    private final ObjectStore objects;
    private final UploadProperties props;
    private final Clock clock;

    public UploadPurge(UploadStore uploads, ObjectStore objects, UploadProperties props, Clock clock) {
        this.uploads = uploads;
        this.objects = objects;
        this.props = props;
        this.clock = clock;
    }

    public int purge() {
        Instant cutoff = clock.instant().minus(props.confirmWindow());
        int purged = 0;
        for (UploadRow upload : uploads.unconfirmedBefore(cutoff)) {
            try {
                objects.delete(upload.s3Key());
            } catch (RuntimeException e) {
                log.warn("Could not delete abandoned upload {}; will retry: {}", upload.id(), e.getMessage());
                continue;
            }
            uploads.delete(upload.id());
            purged++;
        }
        uploads.deleteConfirmedBefore(cutoff);
        return purged;
    }
}
