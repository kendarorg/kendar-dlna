package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jupnp.support.contentdirectory.ContentDirectoryException;
import org.jupnp.support.model.BrowseFlag;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.testutil.Fixtures;

class SortCriteriaTest {

    private static TrackTags tags(String title, String artist, String album, String genre, Integer year, Integer no) {
        return new TrackTags(title, artist, album, null, genre, year, no, null, null);
    }

    private static BrowseEngine engine() {
        List<Track> tracks = List.of(
                Fixtures.track("lib/a.mp3", tags("Banana", "Zed", "Fruit", "Pop", 2001, 2), 1),
                Fixtures.track("lib/b.mp3", tags("apple", "Amy", "Fruit", "Rock", 1999, 10), 1),
                Fixtures.track("lib/c.mp3", tags("Cherry", null, null, null, null, null), 1),
                Fixtures.track("lib/d.mp3", tags("Date", "amy", "Dry", "Pop", 2005, 1), 1));
        SnapshotHolder holder = new SnapshotHolder(Fixtures.snapshot(
                List.of(Fixtures.folder("lib"), Fixtures.folder("lib/sub")), tracks));
        ArtworkService art = new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused"));
        return new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://x", art);
    }

    private static List<String> titles(String didl) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<dc:title>([^<]*)</dc:title>").matcher(didl);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static List<String> browse(String sort) throws Exception {
        return titles(engine().browse(ObjectIds.folder(Fixtures.folder("lib")), BrowseFlag.DIRECT_CHILDREN, "*", 0, 0,
                org.kendar.dlna.shared.ClientId.UNKNOWN, null, sort).getResult());
    }

    @Test
    void parsesKeysIgnoresUnsupportedPropertiesAndRejectsGarbage() throws Exception {
        assertThat(SortCriteria.parse(null)).isEmpty();
        assertThat(SortCriteria.parse(" ")).isEmpty();
        assertThat(SortCriteria.parse("+dc:title,-upnp:artist")).containsExactly(
                new SortCriteria.Key("dc:title", true), new SortCriteria.Key("upnp:artist", false));
        assertThat(SortCriteria.parse("+res@size,+dc:title")).containsExactly(new SortCriteria.Key("dc:title", true));
        assertThatThrownBy(() -> SortCriteria.parse("dc:title")).isInstanceOf(
                SortCriteria.InvalidSortCriteriaException.class);
        assertThatThrownBy(() -> SortCriteria.parse("+dc:title,,-x")).isInstanceOf(
                SortCriteria.InvalidSortCriteriaException.class);
        assertThatThrownBy(() -> SortCriteria.parse("+")).isInstanceOf(SortCriteria.InvalidSortCriteriaException.class);
        assertThat(SortCriteria.CAPABILITIES).contains("dc:title", "upnp:originalTrackNumber", "dc:date");
    }

    @Test
    void sortsByTitleIgnoringCaseAndTheWriterListsContainersFirst() throws Exception {
        assertThat(browse("+dc:title")).containsExactly("sub", "apple", "Banana", "Cherry", "Date");
        assertThat(browse("-dc:title")).containsExactly("sub", "Date", "Cherry", "Banana", "apple");
    }

    @Test
    void sortsByMultipleKeysAndPutsMissingValuesLast() throws Exception {
        assertThat(browse("+upnp:artist,+dc:title")).containsExactly("sub", "apple", "Date", "Banana", "Cherry");
        assertThat(browse("-upnp:artist,+dc:title")).containsExactly("sub", "Banana", "apple", "Date", "Cherry");
        assertThat(browse("+upnp:genre,-dc:date")).containsExactly("sub", "Date", "Banana", "apple", "Cherry");
    }

    @Test
    void sortsNumbersAsNumbersAndByClass() throws Exception {
        assertThat(browse("+upnp:originalTrackNumber")).containsExactly("sub", "Date", "Banana", "apple", "Cherry");
        assertThat(browse("+dc:date")).containsExactly("sub", "apple", "Banana", "Date", "Cherry");
        assertThat(browse("+upnp:class,+dc:title")).containsExactly("sub", "apple", "Banana", "Cherry", "Date");
    }

    @Test
    void sortHappensBeforePaging() throws Exception {
        String page = engine().browse(ObjectIds.folder(Fixtures.folder("lib")), BrowseFlag.DIRECT_CHILDREN, "*", 1, 2,
                org.kendar.dlna.shared.ClientId.UNKNOWN, null, "+dc:title").getResult();
        assertThat(titles(page)).containsExactly("Banana", "Cherry");
    }

    @Test
    void noOrAnUnsupportedSortKeepsTheConfiguredOrder() throws Exception {
        List<String> plain = browse(null);
        assertThat(browse("")).isEqualTo(plain);
        assertThat(browse("+res@size")).isEqualTo(plain);
    }

    @Test
    void malformedCriteriaIsUpnpError709() {
        assertThatThrownBy(() -> browse("title")).isInstanceOfSatisfying(ContentDirectoryException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(709));
    }
}
