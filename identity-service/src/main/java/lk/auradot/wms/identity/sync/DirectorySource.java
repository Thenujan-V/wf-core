package lk.auradot.wms.identity.sync;

/** Where a full sync reads the directory from; chosen by {@code wms.identity.source}. */
public interface DirectorySource {

    DirectorySnapshot load();
}
