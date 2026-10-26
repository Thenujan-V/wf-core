package lk.auradot.wms.document.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static lk.auradot.wms.document.access.Permission.DELETE;
import static lk.auradot.wms.document.access.Permission.MANAGE;
import static lk.auradot.wms.document.access.Permission.READ;
import static lk.auradot.wms.document.access.Permission.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

class AclSnapshotTest {

    /** root → finance → invoices; root → hr (breaks inheritance). */
    static AclSnapshot tree() {
        return new AclSnapshot(
                List.of(new FolderNode("root", null, "Library", true),
                        new FolderNode("finance", "root", "Finance", true),
                        new FolderNode("invoices", "finance", "Invoices", true),
                        new FolderNode("hr", "root", "HR", false)),
                Map.of("root", List.of(new AclEntry(PrincipalType.ROLE, "user", READ)),
                        "finance", List.of(new AclEntry(PrincipalType.GROUP, "g-fin", WRITE),
                                new AclEntry(PrincipalType.USER, "bob", MANAGE)),
                        "hr", List.of(new AclEntry(PrincipalType.USER, "erin", DELETE))));
    }

    static Caller caller(String id, Set<String> groups, String... roles) {
        return new Caller(id, Set.of(roles), groups, "t");
    }

    @Test
    void permissionsAreOrdered() {
        assertThat(MANAGE.covers(READ)).isTrue();
        assertThat(WRITE.covers(DELETE)).isFalse();
        assertThat(READ.covers(READ)).isTrue();
    }

    @Test
    void inheritsDownTheTreeAndTakesTheHighestMatchingEntry() {
        AclSnapshot acl = tree();
        Caller carol = caller("carol", Set.of("g-fin"), "user");
        Caller bob = caller("bob", Set.of("g-fin"), "user");
        Caller dan = caller("dan", Set.of(), "user");

        assertThat(acl.effective("root", carol)).isEqualTo(READ);
        assertThat(acl.effective("invoices", carol)).isEqualTo(WRITE);
        assertThat(acl.effective("invoices", bob)).isEqualTo(MANAGE);
        assertThat(acl.effective("invoices", dan)).isEqualTo(READ);
    }

    @Test
    void aFolderThatBreaksInheritanceUsesOnlyItsOwnEntries() {
        AclSnapshot acl = tree();

        assertThat(acl.effective("hr", caller("carol", Set.of("g-fin"), "user"))).isNull();
        assertThat(acl.effective("hr", caller("erin", Set.of(), "user"))).isEqualTo(DELETE);
        assertThat(acl.inherited("hr")).isEmpty();
        assertThat(acl.inherited("invoices")).extracting(AclEntry::principalId).containsExactlyInAnyOrder("user", "g-fin", "bob");
    }

    @Test
    void libraryAdminsManageEverythingAndUnknownFoldersGiveNothing() {
        AclSnapshot acl = tree();

        assertThat(acl.effective("hr", caller("dave", Set.of(), "user", "document-admin"))).isEqualTo(MANAGE);
        assertThat(acl.effective("hr", caller("alice", Set.of(), "wms-admin"))).isEqualTo(MANAGE);
        assertThat(acl.effective("nope", caller("alice", Set.of(), "wms-admin"))).isNull();
    }

    @Test
    void listsReadableFoldersAncestorsChildrenAndSubtrees() {
        AclSnapshot acl = tree();

        assertThat(acl.readable(caller("carol", Set.of("g-fin"), "user"))).containsExactlyInAnyOrder("root", "finance", "invoices");
        assertThat(acl.ancestors("invoices")).extracting(FolderNode::id).containsExactly("root", "finance");
        assertThat(acl.children("root")).extracting(FolderNode::name).containsExactly("Finance", "HR");
        assertThat(acl.subtree("finance")).containsExactlyInAnyOrder("finance", "invoices");
    }

    @Test
    void previewsAnAclChangeWithoutTouchingTheOriginal() {
        AclSnapshot acl = tree();
        AclSnapshot changed = acl.withAcl("finance", false, List.of(new AclEntry(PrincipalType.USER, "bob", READ)));

        assertThat(changed.effective("finance", caller("bob", Set.of(), "user"))).isEqualTo(READ);
        assertThat(changed.effective("finance", caller("carol", Set.of("g-fin"), "user"))).isNull();
        assertThat(acl.effective("finance", caller("bob", Set.of(), "user"))).isEqualTo(MANAGE);
    }
}
