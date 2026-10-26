package lk.auradot.wms.form.definition;

import java.time.Instant;

public record PublishedForm(String id, String key, int version, String name, Instant publishedAt) {

    static PublishedForm of(StoredForm form) {
        return new PublishedForm(form.id(), form.key(), form.version(), form.name(), form.publishedAt());
    }
}
