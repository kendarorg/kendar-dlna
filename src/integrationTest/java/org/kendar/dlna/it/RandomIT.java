package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/** Random N containers: N tracks from the subtree, one stable snapshot per client (IP + User-Agent). */
@Order(60)
class RandomIT {

    private final Server server = Server.get();
    private final UpnpClient cds = server.upnp();

    /** A folder with a Random N container: the library root of the generated library, else the first one found. */
    private String musicId() {
        Tree t = Tree.shared();
        if (server.managed()) {
            return t.require("Folders/" + LibraryFactory.ROOT).id();
        }
        for (Tree.Node n : t.containers()) {
            if (n.title().startsWith("[Random ")) {
                return n.parentId();
            }
        }
        Assumptions.abort("no folder with a Random N container in this library (settings.json \"random\")");
        return null;
    }

    /** The Random container among the children of a folder, as seen by the given User-Agent. */
    private Optional<String> randomContainer(String folderId, String userAgent) {
        UpnpClient.CdsResult r = cds.browse(folderId, "BrowseDirectChildren", "*", 0, 0, userAgent);
        return r.didl().getContainers().stream().filter(c -> c.getTitle().startsWith("[Random "))
                .map(c -> c.getId()).findFirst();
    }

    private List<String> trackIds(String randomId, String userAgent) {
        UpnpClient.CdsResult r = cds.browse(randomId, "BrowseDirectChildren", "*", 0, 0, userAgent);
        return r.ids();
    }

    @Test
    void randomContainerHasNTracksFromTheSubtree() {
        Assumptions.assumeTrue(server.managed(), "needs the generated library");
        String id = randomContainer(musicId(), "RandomIT/size").orElseThrow();
        UpnpClient.CdsResult r = cds.browse(id, "BrowseDirectChildren", "*", 0, 0, "RandomIT/size");
        assertThat(r.total()).isEqualTo(4);
        assertThat(new HashSet<>(r.ids())).as("distinct tracks").hasSize(4);
        assertThat(r.didl().getItems()).hasSize(4);
        // Random 3 in Alpha Greatest (4 tracks), Random 2 in Mixed (4 tracks in its subtree)
        Tree t = Tree.shared();
        String alpha = t.require("Folders/" + LibraryFactory.ROOT + "/Alpha Greatest").id();
        assertThat(cds.browse(randomContainer(alpha, "RandomIT/size").orElseThrow(), "BrowseDirectChildren", "*", 0, 0,
                "RandomIT/size").total()).isEqualTo(3);
        String mixed = t.require("Folders/" + LibraryFactory.ROOT + "/Mixed").id();
        assertThat(cds.browse(randomContainer(mixed, "RandomIT/size").orElseThrow(), "BrowseDirectChildren", "*", 0, 0,
                "RandomIT/size").total()).isEqualTo(2);
    }

    @Test
    void sameClientKeepsTheSameSnapshot() {
        String folder = musicId();
        String ua = "RandomIT/stable-client";
        String first = randomContainer(folder, ua).orElseThrow();
        List<String> tracks = trackIds(first, ua);
        for (int i = 0; i < 3; i++) {
            String again = randomContainer(folder, ua).orElseThrow();
            assertThat(again).as("same User-Agent, same container id").isEqualTo(first);
            assertThat(trackIds(again, ua)).as("same tracks in the same order").isEqualTo(tracks);
        }
    }

    @Test
    void differentUserAgentsGetDifferentSnapshots() {
        Assumptions.assumeTrue(server.managed(), "needs the generated library");
        String folder = musicId();
        String a = randomContainer(folder, "RandomIT/client-A").orElseThrow();
        Set<String> otherContainers = new HashSet<>();
        Set<List<String>> otherSelections = new HashSet<>();
        List<String> tracksA = trackIds(a, "RandomIT/client-A");
        for (String ua : List.of("RandomIT/client-B", "RandomIT/client-C", "RandomIT/client-D")) {
            String c = randomContainer(folder, ua).orElseThrow();
            otherContainers.add(c);
            otherSelections.add(trackIds(c, ua).stream().map(RandomIT::trackPart).toList());
        }
        assertThat(otherContainers).as("each client has its own snapshot container").doesNotContain(a).hasSize(3);
        // 4 of ~134 tracks: another client drawing exactly the same 4 in the same order has a probability of ~0
        assertThat(otherSelections).as("selections differ between clients")
                .doesNotContain(tracksA.stream().map(RandomIT::trackPart).toList());
    }

    /** The track id of an {@code i:<context>:<trackId>} object id. */
    private static String trackPart(String objectId) {
        return objectId.substring(objectId.lastIndexOf(':') + 1);
    }

    @Test
    void randomItemsStreamLikeAnyOther() {
        String ua = "RandomIT/stream";
        String random = randomContainer(musicId(), ua).orElseThrow();
        UpnpClient.CdsResult r = cds.browse(random, "BrowseDirectChildren", "*", 0, 0, ua);
        var item = r.didl().getItems().get(0);
        var res = item.getResources().get(0);
        assertThat(Http.head(server.rebase(res.getValue())).status()).isEqualTo(200);
    }
}
