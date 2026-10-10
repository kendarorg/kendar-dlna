package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.DIDLObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/** ContentDirectory Search: criteria, class filters, scope, paging and the 708/710 errors. */
@Order(50)
@TestMethodOrder(MethodOrderer.MethodName.class)
class SearchIT {

    private final Server server = Server.get();
    private final UpnpClient cds = server.upnp();

    private static List<String> titles(UpnpClient.CdsResult r) {
        DIDLContent d = r.didl();
        List<String> out = new ArrayList<>();
        d.getContainers().forEach(c -> out.add(c.getTitle()));
        d.getItems().forEach(i -> out.add(i.getTitle()));
        return out;
    }

    private void managedOnly() {
        Assumptions.assumeTrue(server.managed(), "needs the generated library");
    }

    @Test
    void titleContainsFindsItemsAndFoldersCaseInsensitively() {
        managedOnly();
        UpnpClient.CdsResult r = cds.search("0", "dc:title contains \"ALPHA\"", 0, 0);
        assertThat(titles(r)).contains("Alpha Greatest", "Alpha Two", "Alpha Three", "Alpha Four", "01 Alpha One");
        assertThat(r.total()).isEqualTo(r.returned()).isEqualTo(titles(r).size());
        for (String t : titles(r)) {
            assertThat(t.toLowerCase()).contains("alpha");
        }
    }

    @Test
    void titleContainsIgnoresAccents() {
        managedOnly();
        assertThat(titles(cds.search("0", "dc:title contains \"test\"", 0, 0))).contains("Tëst – ü");
        assertThat(titles(cds.search("0", "dc:title contains \"unicode\"", 0, 0))).contains("Ünïcode ñ");
    }

    @Test
    void classFiltersSeparateItemsFromContainers() {
        UpnpClient.CdsResult items = cds.search("0", "upnp:class derivedfrom \"object.item.audioItem\"", 0, 0);
        assertThat(items.total()).isPositive();
        assertThat(items.didl().getContainers()).isEmpty();
        assertThat(items.didl().getItems()).hasSize((int) items.total());
        UpnpClient.CdsResult folders = cds.search("0", "upnp:class derivedfrom \"object.container\"", 0, 0);
        assertThat(folders.didl().getItems()).isEmpty();
        assertThat(folders.total()).isPositive();
        if (server.managed()) {
            // every track once: the Random N and view duplicates are excluded
            assertThat(items.total()).isEqualTo(server.library().trackCount());
        }
    }

    @Test
    void searchResultsAreTheSameObjectsBrowseReturns() {
        UpnpClient.CdsResult r = cds.search("0", "upnp:class derivedfrom \"object.item.audioItem\"", 0, 40);
        for (var item : r.didl().getItems()) {
            UpnpClient.CdsResult meta = cds.metadata(item.getId());
            assertThat(meta.didl().getItems()).hasSize(1);
            assertThat(meta.didl().getItems().get(0).getTitle()).isEqualTo(item.getTitle());
            assertThat(item.getResources()).as("res of search hit %s", item.getTitle()).isNotEmpty();
        }
    }

    @Test
    void artistAlbumGenreAndCreatorCriteria() {
        managedOnly();
        assertThat(titles(cds.search("0", "upnp:artist contains \"testers\"", 0, 0)))
                .contains("Alpha Four");
        assertThat(titles(cds.search("0", "upnp:album contains \"sessions\"", 0, 0))).contains("Beta Tagged");
        assertThat(titles(cds.search("0", "upnp:genre = \"Jazz\"", 0, 0))).containsExactly("Beta Tagged");
        assertThat(titles(cds.search("0", "dc:creator contains \"other band\"", 0, 0))).contains("Beta Tagged");
    }

    @Test
    void booleanOperatorsAndParentheses() {
        managedOnly();
        List<String> or = titles(cds.search("0", "(dc:title contains \"Alpha Four\" or dc:title contains \"Beta Tagged\")"
                + " and upnp:class derivedfrom \"object.item.audioItem\"", 0, 0));
        assertThat(or).containsExactlyInAnyOrder("Alpha Four", "Beta Tagged");
        assertThat(titles(cds.search("0", "dc:title contains \"Alpha\" and upnp:genre = \"Jazz\"", 0, 0))).isEmpty();
        assertThat(cds.search("0", "dc:title exists true", 0, 1).total()).isPositive();
    }

    @Test
    void wildcardMatchesEverything() {
        UpnpClient.CdsResult all = cds.search("0", "*", 0, 5);
        assertThat(all.returned()).isEqualTo(Math.min(5, all.total()));
        assertThat(all.total()).isGreaterThanOrEqualTo(all.returned());
    }

    @Test
    void containerScopeLimitsTheSearchToTheSubtree() {
        managedOnly();
        Tree t = Tree.shared();
        Tree.Node beta = t.require("Folders/" + LibraryFactory.ROOT + "/Beta Album");
        List<String> inBeta = titles(cds.search(beta.id(), "upnp:class derivedfrom \"object.item.audioItem\"", 0, 0));
        assertThat(inBeta).containsExactlyInAnyOrder("Opus Title", "Beta Aiff", "Beta Wave", "Beta Midi", "Beta Tagged");
        assertThat(cds.search(beta.id(), "dc:title contains \"Alpha\"", 0, 0).total()).isZero();
        Tree.Node deep = t.require("Folders/" + LibraryFactory.ROOT + "/Mixed/Deep");
        assertThat(titles(cds.search(deep.id(), "upnp:class derivedfrom \"object.item.audioItem\"", 0, 0)))
                .containsExactlyInAnyOrder("Deep One", "Deeper Still");
    }

    @Test
    void randomAndViewItemsAreNotSearchResults() {
        managedOnly();
        // "Track 001" exists once on disk but is also listed in the Titles and Albums views
        UpnpClient.CdsResult r = cds.search("0", "dc:title = \"Track 001\"", 0, 0);
        assertThat(r.total()).isEqualTo(1);
        assertThat(r.didl().getItems().get(0).getId()).startsWith("t:");
    }

    @Test
    void pagingOfSearchResults() {
        managedOnly();
        String criteria = "dc:title contains \"Track\"";
        UpnpClient.CdsResult all = cds.search("0", criteria, 0, 0);
        assertThat(all.total()).isEqualTo(LibraryFactory.MANY);
        List<String> paged = new ArrayList<>();
        for (long s = 0; s < all.total(); s += 25) {
            UpnpClient.CdsResult p = cds.search("0", criteria, s, 25);
            assertThat(p.total()).isEqualTo(all.total());
            assertThat(p.returned()).isEqualTo(Math.min(25, all.total() - s));
            paged.addAll(p.ids());
        }
        assertThat(paged).isEqualTo(all.ids());
        assertThat(cds.search("0", criteria, all.total() + 10, 25).returned()).isZero();
    }

    @Test
    void malformedCriteriaAreError708() {
        for (String bad : new String[] { "dc:title contains", "dc:title contains \"x", "(dc:title contains \"x\"",
                "dc:title foo \"x\"", "dc:title contains \"x\" and", "unknown:prop = \"x\" )", "and or" }) {
            assertThatThrownBy(() -> cds.search("0", bad, 0, 10)).as(bad).isInstanceOf(UpnpClient.UpnpFailure.class)
                    .satisfies(e -> assertThat(((UpnpClient.UpnpFailure) e).code()).as(bad).isEqualTo(708));
        }
    }

    @Test
    void unknownContainerIsError710() {
        assertThatThrownBy(() -> cds.search("no-such-container", "dc:title contains \"x\"", 0, 10))
                .isInstanceOf(UpnpClient.UpnpFailure.class)
                .satisfies(e -> assertThat(((UpnpClient.UpnpFailure) e).code()).isEqualTo(710));
    }

    @Test
    void searchHitsCarryOnlyKnownObjectIds() {
        UpnpClient.CdsResult r = cds.search("0", "upnp:class derivedfrom \"object.item.audioItem\"", 0, 10);
        for (DIDLObject o : r.didl().getItems()) {
            assertThat(o.getId()).isNotBlank();
            assertThat(o.getParentID()).isNotBlank();
        }
    }
}
