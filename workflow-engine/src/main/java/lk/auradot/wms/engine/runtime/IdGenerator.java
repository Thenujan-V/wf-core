package lk.auradot.wms.engine.runtime;

import java.util.UUID;

public interface IdGenerator {

    String next();

    static IdGenerator uuids() {
        return () -> UUID.randomUUID().toString();
    }
}
