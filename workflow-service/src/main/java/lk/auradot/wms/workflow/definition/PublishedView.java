package lk.auradot.wms.workflow.definition;

import java.time.Instant;

public record PublishedView(String id, String key, int version, String name, Instant publishedAt) {

    static PublishedView of(StoredDefinition definition) {
        return new PublishedView(definition.id(), definition.key(), definition.version(), definition.name(),
                definition.publishedAt());
    }
}
