package org.kendar.dlna.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.jupnp.support.model.DIDLObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/** Album art: every {@code upnp:albumArtURI} in the tree returns an image with the right headers. */
@Order(40)
class ArtIT {

    private final Server server = Server.get();

    /** art url (as reachable from here) -> first object that uses it */
    private Map<String, Tree.Node> artUrls() {
        Map<String, Tree.Node> urls = new LinkedHashMap<>();
        for (Tree.Node n : Tree.shared().nodes.values()) {
            URI art = n.object().getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
            if (art != null) {
                urls.putIfAbsent(server.rebase(art.toString()), n);
            }
        }
        return urls;
    }

    @Test
    void everyArtUrlReturnsADecodableJpeg() throws IOException {
        Map<String, Tree.Node> urls = artUrls();
        Assumptions.assumeTrue(!urls.isEmpty(), "this library exposes no artwork");
        if (server.managed()) {
            assertThat(urls).as("art urls of the generated library (front.jpg, embedded cover)").isNotEmpty();
        }
        int checked = 0;
        for (Map.Entry<String, Tree.Node> e : urls.entrySet()) {
            if (!server.managed() && checked++ >= ItEnv.maxStreamedItems()) {
                break;
            }
            Http.Response g = Http.get(e.getKey());
            String what = e.getKey() + " (" + e.getValue().path() + ")";
            assertThat(g.status()).as(what).isEqualTo(200);
            assertThat(g.header("content-type")).as("content type of " + what).hasValueSatisfying(
                    v -> assertThat(v).startsWith("image/"));
            assertThat(g.header("content-length")).as(what).hasValue(Integer.toString(g.body().length));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(g.body()));
            assertThat(img).as("decodable image of " + what).isNotNull();
            assertThat(img.getWidth()).isPositive();

            Http.Response h = Http.head(e.getKey());
            assertThat(h.status()).as("HEAD " + what).isEqualTo(200);
            assertThat(h.header("content-length")).isEqualTo(g.header("content-length"));
        }
    }

    @Test
    void artOfTheGeneratedFoldersIsServedAndItemsInheritIt() {
        Assumptions.assumeTrue(server.managed(), "needs the generated library");
        Tree t = Tree.shared();
        Tree.Node alpha = t.require("Folders/" + LibraryFactory.ROOT + "/Alpha Greatest");
        URI art = alpha.object().getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        assertThat(art).as("front.jpg of the folder").isNotNull();
        Tree.Node track = t.require("Folders/" + LibraryFactory.ROOT + "/Alpha Greatest/Alpha Two");
        assertThat(track.object().getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class))
                .as("a track uses the art of its folder").isNotNull();
        // the folder art and the track art are the same picture
        Http.Response a = Http.get(server.rebase(art.toString()));
        URI trackArt = track.object().getFirstPropertyValue(DIDLObject.Property.UPNP.ALBUM_ART_URI.class);
        assertThat(Http.get(server.rebase(trackArt.toString())).body()).isEqualTo(a.body());
    }

    @Test
    void unknownArtIs404() {
        assertThat(Http.get(server.baseUrl() + "/art/nosuch").status()).isEqualTo(404);
        assertThat(Http.get(server.baseUrl() + "/art/nosuch/none.jpg").status()).isEqualTo(404);
    }
}
