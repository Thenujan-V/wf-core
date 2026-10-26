package lk.auradot.wms.document.access;

import lk.auradot.wms.document.DocumentIntegrationTest;
import lk.auradot.wms.document.TestDb;
import lk.auradot.wms.document.folder.FolderStore;
import lk.auradot.wms.document.folder.Folders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@DocumentIntegrationTest
class AclSnapshotsTest {

    @Autowired JdbcClient jdbc;
    @Autowired AclSnapshots snapshots;
    @Autowired FolderStore folders;
    @Autowired TransactionTemplate tx;

    final Caller carol = new Caller("carol", Set.of("user"), Set.of(), "t");

    @BeforeEach
    void reset() {
        TestDb.clear(jdbc);
        snapshots.invalidate();
    }

    @Test
    void aSnapshotLoadedByAnotherRequestWhileAChangeCommitsIsNotKept() {
        assertThat(snapshots.current().effective(Folders.ROOT_ID, carol)).isEqualTo(Permission.READ);

        tx.executeWithoutResult(status -> {
            folders.replaceAcl(Folders.ROOT_ID, true, List.of(
                    new AclEntry(PrincipalType.ROLE, "user", Permission.READ),
                    new AclEntry(PrincipalType.ROLE, "document-admin", Permission.MANAGE),
                    new AclEntry(PrincipalType.USER, "carol", Permission.MANAGE)));
            snapshots.invalidate();
            // Another request on this replica reloads before the commit and sees the old, committed ACL.
            CompletableFuture.runAsync(snapshots::current).join();
        });

        assertThat(snapshots.current().effective(Folders.ROOT_ID, carol)).isEqualTo(Permission.MANAGE);
    }
}
