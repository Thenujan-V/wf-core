package lk.auradot.wms.document.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every folder and its ACL entries at one moment. Effective permissions are computed from it in memory (spec §6):
 * a folder's own entries plus its ancestors', up to and including the first folder that breaks inheritance; the
 * highest permission a caller matches wins.
 */
public final class AclSnapshot {

    private final Map<String, FolderNode> folders;
    private final Map<String, List<AclEntry>> entries;
    private final Map<String, List<FolderNode>> children = new HashMap<>();

    public AclSnapshot(Collection<FolderNode> folders, Map<String, List<AclEntry>> entries) {
        this.folders = new LinkedHashMap<>();
        folders.forEach(f -> this.folders.put(f.id(), f));
        this.entries = Map.copyOf(entries);
        for (FolderNode f : folders) {
            if (f.parentId() != null) {
                children.computeIfAbsent(f.parentId(), k -> new ArrayList<>()).add(f);
            }
        }
        children.values().forEach(list -> list.sort(Comparator.comparing(FolderNode::name, String.CASE_INSENSITIVE_ORDER)));
    }

    public Optional<FolderNode> folder(String id) {
        return Optional.ofNullable(folders.get(id));
    }

    /** The caller's permission on the folder; null when they have none or the folder does not exist. */
    public Permission effective(String folderId, Caller caller) {
        FolderNode node = folders.get(folderId);
        if (node == null) {
            return null;
        }
        if (caller.isAdmin()) {
            return Permission.MANAGE;
        }
        Permission best = null;
        while (node != null) {
            for (AclEntry entry : entries.getOrDefault(node.id(), List.of())) {
                if (caller.matches(entry) && (best == null || entry.permission().covers(best))) {
                    best = entry.permission();
                }
            }
            if (!node.inheritAcl() || node.parentId() == null) {
                break;
            }
            node = folders.get(node.parentId());
        }
        return best;
    }

    public Set<String> readable(Caller caller) {
        Set<String> readable = new HashSet<>();
        for (String id : folders.keySet()) {
            if (effective(id, caller) != null) {
                readable.add(id);
            }
        }
        return readable;
    }

    public List<AclEntry> entriesOf(String folderId) {
        return entries.getOrDefault(folderId, List.of());
    }

    /** The entries a folder receives from its ancestors; empty when it breaks inheritance. */
    public List<AclEntry> inherited(String folderId) {
        List<AclEntry> inherited = new ArrayList<>();
        FolderNode node = folders.get(folderId);
        while (node != null && node.inheritAcl() && node.parentId() != null) {
            node = folders.get(node.parentId());
            if (node == null) {
                break;
            }
            inherited.addAll(entriesOf(node.id()));
        }
        return inherited;
    }

    /** From the root down to the folder's parent. */
    public List<FolderNode> ancestors(String folderId) {
        List<FolderNode> path = new ArrayList<>();
        FolderNode node = folders.get(folderId);
        while (node != null && node.parentId() != null) {
            node = folders.get(node.parentId());
            if (node != null) {
                path.addFirst(node);
            }
        }
        return path;
    }

    public List<FolderNode> children(String folderId) {
        return children.getOrDefault(folderId, List.of());
    }

    /** The folder and everything below it. */
    public Set<String> subtree(String folderId) {
        Set<String> ids = new HashSet<>();
        List<String> todo = new ArrayList<>(List.of(folderId));
        while (!todo.isEmpty()) {
            String id = todo.removeLast();
            if (folders.containsKey(id) && ids.add(id)) {
                children(id).forEach(child -> todo.add(child.id()));
            }
        }
        return ids;
    }

    /** The same snapshot with one folder's ACL replaced, to check a change before saving it. */
    public AclSnapshot withAcl(String folderId, boolean inheritAcl, List<AclEntry> newEntries) {
        List<FolderNode> nodes = folders.values().stream()
                .map(f -> f.id().equals(folderId) ? new FolderNode(f.id(), f.parentId(), f.name(), inheritAcl) : f)
                .toList();
        Map<String, List<AclEntry>> copy = new HashMap<>(entries);
        copy.put(folderId, List.copyOf(newEntries));
        return new AclSnapshot(nodes, copy);
    }
}
