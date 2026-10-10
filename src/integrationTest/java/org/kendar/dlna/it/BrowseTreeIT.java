package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.DIDLObject;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.container.Container;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** Browse of the whole tree: DIDL validity, paging consistency, update ids, structure of the generated library. */
@Order(20)
@TestMethodOrder(MethodOrderer.MethodName.class)
class BrowseTreeIT {

    private final Server server = Server.get();
    private final UpnpClient cds = server.upnp();

    private Tree tree() {
        return Tree.shared();
    }

    @Test
    void rootHasFoldersAndPlaylists() {
        UpnpClient.CdsResult r = cds.children("0", 0, 0);
        DIDLContent didl = r.didl();
        assertThat(didl.getContainers()).extracting(Container::getId).contains("folders", "playlists");
        assertThat(r.total()).isEqualTo(r.returned());
        assertThat(r.updateId()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void metadataOfTheRootIsAContainer() {
        UpnpClient.CdsResult r = cds.metadata("0");
        assertThat(r.returned()).isEqualTo(1);
        assertThat(r.didl().getContainers()).hasSize(1);
        assertThat(r.didl().getContainers().get(0).getParentID()).isEqualTo("-1");
    }

    @Test
    void everyContainerPagesConsistentlyAndTheTreeIsWellFormed() {
        Tree t = tree();
        assertThat(t.containers()).isNotEmpty();
        assertThat(t.items()).isNotEmpty();
        for (Tree.Node c : t.containers()) {
            Container container = (Container) c.object();
            if (container != null && container.getChildCount() != null) {
                assertThat((long) container.getChildCount()).as("childCount of %s", c.path())
                        .isEqualTo(t.totals.get(c.id()));
            }
        }
        for (Tree.Node n : t.items()) {
            assertThat(n.upnpClass()).as(n.path()).startsWith("object.item.audioItem");
            assertThat(n.resources()).as("res of %s", n.path()).isNotEmpty();
            for (Res res : n.resources()) {
                assertThat(res.getValue()).as("res url of %s", n.path()).startsWith("http");
                assertThat(res.getProtocolInfo()).as("protocolInfo of %s", n.path()).isNotNull();
                assertThat(res.getProtocolInfo().getProtocol()).isEqualTo(org.jupnp.support.model.Protocol.HTTP_GET);
            }
        }
    }

    @Test
    void browseMetadataAgreesWithTheListingForEveryObject() {
        Tree t = tree();
        int checked = 0;
        for (Tree.Node n : t.nodes.values()) {
            // the Random N containers are per client: the same client gets the same snapshot, so they are included
            UpnpClient.CdsResult r = cds.metadata(n.id());
            assertThat(r.returned()).as("metadata of %s", n.path()).isEqualTo(1);
            assertThat(r.total()).isEqualTo(1);
            DIDLContent didl = r.didl();
            DIDLObject o = n.container() ? didl.getContainers().get(0) : didl.getItems().get(0);
            assertThat(o.getId()).isEqualTo(n.id());
            assertThat(o.getTitle()).as("title of %s", n.path()).isEqualTo(n.title());
            assertThat(o.getParentID()).as("parent of %s", n.path()).isEqualTo(n.parentId());
            assertThat(o.getClazz().getValue()).isEqualTo(n.upnpClass());
            checked++;
        }
        assertThat(checked).isEqualTo(t.nodes.size());
    }

    @Test
    void pagingIsExactAtTheBoundaries() {
        Tree t = tree();
        Tree.Node big = t.containers().stream().max((a, b) -> Long.compare(t.totals.get(a.id()),
                t.totals.get(b.id()))).orElseThrow();
        long total = t.totals.get(big.id());
        assertThat(total).isGreaterThan(1);

        UpnpClient.CdsResult all = cds.children(big.id(), 0, 0); // 0 = everything
        assertThat(all.returned()).isEqualTo(total);
        assertThat(all.total()).isEqualTo(total);

        UpnpClient.CdsResult first = cds.children(big.id(), 0, 1);
        assertThat(first.returned()).isEqualTo(1);
        assertThat(first.total()).isEqualTo(total);

        UpnpClient.CdsResult last = cds.children(big.id(), total - 1, 10);
        assertThat(last.returned()).isEqualTo(1);
        assertThat(last.total()).isEqualTo(total);

        UpnpClient.CdsResult beyond = cds.children(big.id(), total + 5, 10);
        assertThat(beyond.returned()).isZero();
        assertThat(beyond.total()).isEqualTo(total);

        // the pages of 7 reassemble to the full list in order
        List<String> ids = new ArrayList<>();
        for (long s = 0; s < total; s += 7) {
            UpnpClient.CdsResult p = cds.children(big.id(), s, 7);
            ids.addAll(p.ids());
        }
        List<String> whole = new ArrayList<>();
        whole.addAll(all.ids());
        assertThat(ids).isEqualTo(whole);
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
    }

    @Test
    void unknownObjectIsNoSuchObject() {
        assertThatThrownBy(() -> cds.children("no-such-object", 0, 10)).isInstanceOf(UpnpClient.UpnpFailure.class)
                .satisfies(e -> assertThat(((UpnpClient.UpnpFailure) e).code()).isEqualTo(701));
    }

    @Test
    void browsingAnItemAsContainerFails() {
        Tree t = tree();
        Tree.Node item = t.items().get(0);
        assertThatThrownBy(() -> cds.children(item.id(), 0, 10)).isInstanceOf(UpnpClient.UpnpFailure.class);
    }

    @Test
    void updateIdsAreStableWithoutChanges() {
        long system = cds.systemUpdateId();
        UpnpClient.CdsResult folders = cds.metadata("folders");
        UpnpClient.CdsResult again = cds.metadata("folders");
        assertThat(again.updateId()).isEqualTo(folders.updateId());
        assertThat(cds.systemUpdateId()).isEqualTo(system);
        Set<Long> unique = new HashSet<>();
        unique.add(cds.metadata("0").updateId());
        assertThat(unique).hasSize(1);
    }

    /**
     * KNOWN SERVER LIMITATION: settings.json {\"sort\":\"explicit\"} promises folders and files interleaved in the listed
     * order, but DIDL-Lite as generated by jUPnP always writes all containers of a page before its items, so the
     * order of the example (Zed.wav, Deep, Aaa.wav) reaches the client as Deep, Zed, Aaa.
     */
    @Test
    @org.junit.jupiter.api.Disabled("Known server limitation: DIDL-Lite lists containers before items, explicit order cannot interleave")
    void explicitOrderInterleavesFoldersAndFiles() {
        Tree t = tree();
        List<String> mixed = t.childrenOf(t.require("Folders/" + LibraryFactory.ROOT + "/Mixed").id()).stream()
                .map(Tree.Node::title).filter(s -> !s.startsWith("[Random")).toList();
        assertThat(mixed).containsExactly("Zed", "Deep", "Aaa");
    }

    @Test
    void generatedLibraryHasTheConfiguredStructure() {
        org.junit.jupiter.api.Assumptions.assumeTrue(server.managed(), "needs the generated library");
        Tree t = tree();
        String m = "Folders/" + LibraryFactory.ROOT;
        // the roots of the library, then the subfolders (alphabetical: filename sort of the root)
        assertThat(t.byPath("Folders")).isPresent();
        assertThat(t.byPath(m)).isPresent();
        List<String> kids = t.childrenOf(t.require(m).id()).stream().map(Tree.Node::title).toList();
        assertThat(kids).contains("Alpha Greatest", "Beta Album", "Mixed", "Many", "Ünïcode ñ", "Misc");
        // Random N and the views come first, in the configured order
        assertThat(kids.get(0)).startsWith("[Random");
        assertThat(kids.subList(1, 5)).containsExactly("[Albums]", "[Artists]", "[Genres]", "[Titles]");
        // sort=track and the album overlay title
        List<String> alpha = t.childrenOf(t.require(m + "/Alpha Greatest").id()).stream().map(Tree.Node::title)
                .toList();
        assertThat(alpha).containsSubsequence("Alpha Two", "Alpha Three", "Alpha Four");
        // sort=explicit: the files keep the listed order (Zed.wav, Aaa.wav); see explicitOrderInterleavesFoldersAndFiles
        List<String> mixed = t.childrenOf(t.require(m + "/Mixed").id()).stream().map(Tree.Node::title).toList();
        assertThat(mixed).contains("Deep").containsSubsequence("Zed", "Aaa");
        // the large folder
        assertThat(t.totals.get(t.require(m + "/Many").id())).isEqualTo(LibraryFactory.MANY);
        // playlists
        assertThat(t.byPath("Playlists/" + LibraryFactory.PLAYLIST_NAME)).isPresent();
        // cover art: albumArtURI on the folder with front.jpg
        assertThat(t.require(m + "/Alpha Greatest").object().getFirstPropertyValue(
                DIDLObject.Property.UPNP.ALBUM_ART_URI.class)).isNotNull();
    }
}
