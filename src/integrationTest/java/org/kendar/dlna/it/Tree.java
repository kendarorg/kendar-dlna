package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.DIDLObject;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.item.Item;

/** The whole ContentDirectory tree, read through Browse with paging; validates the structure while walking. */
final class Tree {

    /** One object with its position: {@code path} is the titles from the root, joined by "/". */
    record Node(String id, String parentId, String title, String upnpClass, boolean container, DIDLObject object,
            String path, int depth) {
        List<Res> resources() {
            return object.getResources();
        }
    }

    final Map<String, Node> nodes = new LinkedHashMap<>();
    /** Containers by id with the TotalMatches seen while walking. */
    final Map<String, Long> totals = new LinkedHashMap<>();

    static final int PAGE = 37;

    private static Tree shared;

    /** The walk of the default client (no User-Agent), done once per JVM and written to build/ for inspection. */
    static synchronized Tree shared() {
        if (shared == null) {
            shared = walk(Server.get().upnp(), null);
            shared.dump();
        }
        return shared;
    }

    private void dump() {
        try {
            java.nio.file.Path f = ItEnv.projectDir().resolve("build/integration-test-tree.txt");
            java.nio.file.Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            for (Node n : inTreeOrder()) {
                lines.add("  ".repeat(n.depth()) + (n.container() ? "[C] " : "    ") + n.title() + "  <" + n.id()
                        + "> " + n.upnpClass() + (n.container() ? " total=" + totals.get(n.id()) : ""));
            }
            java.nio.file.Files.write(f, lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            // diagnostics only
        }
    }

    static Tree walk(UpnpClient cds, String userAgent) {
        Tree t = new Tree();
        Deque<Node> todo = new ArrayDeque<>();
        todo.add(new Node("0", "-1", "root", "object.container", true, null, "", 0));
        while (!todo.isEmpty()) {
            Node c = todo.poll();
            assertThat(t.nodes.size()).as("tree size (runaway walk?)").isLessThan(20_000);
            List<Node> kids = t.children(cds, c, userAgent);
            for (Node k : kids) {
                assertThat(t.nodes.putIfAbsent(k.id(), k)).as("object id %s seen twice", k.id()).isNull();
                if (k.container()) {
                    todo.add(k);
                }
            }
        }
        return t;
    }

    /** All children of a container, page by page; checks the paging contract on the way. */
    private List<Node> children(UpnpClient cds, Node parent, String userAgent) {
        List<Node> out = new ArrayList<>();
        long total = -1;
        long start = 0;
        do {
            UpnpClient.CdsResult r = cds.browse(parent.id(), "BrowseDirectChildren", "*", start, PAGE, userAgent);
            if (total < 0) {
                total = r.total();
            }
            assertThat(r.total()).as("TotalMatches of %s must not change while paging", parent.id()).isEqualTo(total);
            DIDLContent didl = r.didl();
            List<DIDLObject> objs = new ArrayList<>();
            objs.addAll(didl.getContainers());
            objs.addAll(didl.getItems());
            assertThat((long) objs.size()).as("NumberReturned of %s at %d", parent.id(), start).isEqualTo(r.returned());
            assertThat(r.returned()).isLessThanOrEqualTo(PAGE);
            if (start + PAGE < total) {
                assertThat(r.returned()).as("a full page while more remain (%s at %d)", parent.id(), start)
                        .isEqualTo(PAGE);
            } else {
                assertThat(r.returned()).as("last page of %s", parent.id()).isEqualTo(total - start);
            }
            for (DIDLObject o : orderedAsReturned(r.ids(), didl)) {
                assertThat(o).isNotNull();
                assertThat(o.getParentID()).as("parentID of %s", o.getId()).isEqualTo(parent.id());
                assertThat(o.getTitle()).as("title of %s", o.getId()).isNotBlank();
                assertThat(o.getClazz()).as("class of %s", o.getId()).isNotNull();
                boolean container = o instanceof Container;
                String path = parent.path().isEmpty() ? o.getTitle() : parent.path() + "/" + o.getTitle();
                out.add(new Node(o.getId(), o.getParentID(), o.getTitle(), o.getClazz().getValue(), container, o,
                        path, parent.depth() + 1));
            }
            start += r.returned();
            if (r.returned() == 0) {
                break;
            }
        } while (start < total);
        assertThat((long) out.size()).as("children collected for %s", parent.id()).isEqualTo(total);
        totals.put(parent.id(), total);
        return out;
    }

    /** The objects in the order of the DIDL document. */
    private static List<DIDLObject> orderedAsReturned(List<String> ids, DIDLContent didl) {
        Map<String, DIDLObject> byId = new LinkedHashMap<>();
        didl.getContainers().forEach(c -> byId.put(c.getId(), c));
        didl.getItems().forEach(i -> byId.put(i.getId(), i));
        assertThat(byId.keySet()).as("objects of the page").containsExactlyInAnyOrderElementsOf(ids);
        List<DIDLObject> all = new ArrayList<>();
        ids.forEach(id -> all.add(byId.get(id)));
        return all;
    }

    /** Depth first, in listing order (for the diagnostic dump). */
    List<Node> inTreeOrder() {
        List<Node> out = new ArrayList<>();
        collect("0", out);
        return out;
    }

    private void collect(String id, List<Node> out) {
        for (Node n : childrenOf(id)) {
            out.add(n);
            if (n.container()) {
                collect(n.id(), out);
            }
        }
    }

    List<Node> items() {
        return nodes.values().stream().filter(n -> !n.container()).toList();
    }

    List<Node> containers() {
        return nodes.values().stream().filter(Node::container).toList();
    }

    Optional<Node> byPath(String path) {
        return nodes.values().stream().filter(n -> n.path().equals(path)).findFirst();
    }

    Node require(String path) {
        return byPath(path).orElseThrow(() -> new AssertionError("No object at " + path + "; have "
                + nodes.values().stream().filter(Node::container).map(Node::path).toList()));
    }

    List<Node> childrenOf(String id) {
        return nodes.values().stream().filter(n -> n.parentId().equals(id)).toList();
    }

    static boolean isAudioItem(Node n) {
        return !n.container() && n.object() instanceof Item;
    }
}
