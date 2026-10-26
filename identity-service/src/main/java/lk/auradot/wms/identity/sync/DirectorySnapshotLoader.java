package lk.auradot.wms.identity.sync;

import org.springframework.stereotype.Component;

@Component
public class DirectorySnapshotLoader {

    private final DirectorySource source;

    public DirectorySnapshotLoader(DirectorySource source) {
        this.source = source;
    }

    public DirectorySnapshot load() {
        return source.load();
    }
}
