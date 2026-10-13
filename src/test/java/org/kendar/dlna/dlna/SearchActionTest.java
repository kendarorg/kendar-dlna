package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.SortCriterion;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.jupnp.support.contentdirectory.ContentDirectoryException;

class SearchActionTest {

    private final Folder lib = folder("lib");
    private final Folder rock = folder("lib/Rock");
    private final Folder pop = folder("lib/Pop");
    private final LibraryContentDirectory service;
    private final Track first;

    SearchActionTest() {
        List<Track> tracks = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            tracks.add(track(String.format("lib/Rock/%02d.mp3", i),
                    new TrackTags("Love " + i, "Café Band", "Album", null, "Rock", null, i, null, null), 0));
        }
        tracks.add(track("lib/Pop/x.mp3", new TrackTags("Love pop", "Other", "Hits", null, "Pop", null, 1, null, null), 0));
        first = tracks.get(0);
        SnapshotHolder holder = new SnapshotHolder(snapshot(List.of(lib, rock, pop), tracks));
        ArtworkService art = new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused"));
        service = new LibraryContentDirectory(new BrowseEngine(holder, new DefaultStreamUrlBuilder(),
                () -> "http://h:1", art));
    }

    private BrowseResult search(String container, String criteria, String filter, long start, long count)
            throws Exception {
        return service.search(container, criteria, filter, start, count, new SortCriterion[0]);
    }

    private static DIDLContent parse(BrowseResult r) throws Exception {
        return new DIDLParser().parse(r.getResult());
    }

    @Test
    void findsTracksWithExactTotalAndPaging() throws Exception {
        String crit = "upnp:class derivedfrom \"object.item.audioItem\" and dc:title contains \"love\"";
        BrowseResult all = search("0", crit, "*", 0, 0);
        assertThat(all.getTotalMatchesLong()).isEqualTo(26);
        assertThat(all.getCountLong()).isEqualTo(26);

        BrowseResult page = search("0", crit, "*", 10, 5);
        assertThat(page.getTotalMatchesLong()).isEqualTo(26);
        assertThat(page.getCountLong()).isEqualTo(5);
        DIDLContent didl = parse(page);
        assertThat(didl.getItems()).hasSize(5);
        assertThat(didl.getItems().get(0).getTitle()).isEqualTo("Love 10");

        BrowseResult tail = search("0", crit, "*", 24, 100);
        assertThat(tail.getCountLong()).isEqualTo(2);
        assertThat(tail.getTotalMatchesLong()).isEqualTo(26);

        BrowseResult beyond = search("0", crit, "*", 500, 10);
        assertThat(beyond.getCountLong()).isZero();
        assertThat(beyond.getTotalMatchesLong()).isEqualTo(26);

        BrowseResult negative = search("0", crit, "*", -5, 2);
        assertThat(negative.getCountLong()).isEqualTo(2);
    }

    @Test
    void pagesDoNotOverlapAndCoverEverything() throws Exception {
        String crit = "upnp:class derivedfrom \"object.item.audioItem\"";
        List<String> ids = new ArrayList<>();
        for (long start = 0; start < 30; start += 7) {
            parse(search("0", crit, "*", start, 7)).getItems().forEach(i -> ids.add(i.getId()));
        }
        assertThat(ids).hasSize(26).doesNotHaveDuplicates();
    }

    @Test
    void foldersAreReturnedWithParentIds() throws Exception {
        BrowseResult r = search("0", "dc:title contains \"rock\"", "*", 0, 0);
        DIDLContent didl = parse(r);
        assertThat(didl.getContainers()).hasSize(1);
        assertThat(didl.getContainers().get(0).getTitle()).isEqualTo("Rock");
        assertThat(didl.getContainers().get(0).getParentID()).isEqualTo("f:" + lib.id());
        assertThat(didl.getItems()).isEmpty();
    }

    @Test
    void rootFolderHitHasFoldersAsParent() throws Exception {
        DIDLContent didl = parse(search("0", "dc:title = \"lib\"", "*", 0, 0));
        assertThat(didl.getContainers().get(0).getParentID()).isEqualTo("folders");
    }

    @Test
    void containerScopeLimitsTheSearch() throws Exception {
        BrowseResult pop2 = search(ObjectIds.folder(pop), "dc:title contains \"love\"", "*", 0, 0);
        assertThat(pop2.getTotalMatchesLong()).isEqualTo(1);
        assertThat(parse(pop2).getItems().get(0).getTitle()).isEqualTo("Love pop");
        assertThat(search(ObjectIds.folder(rock), "dc:title contains \"pop\"", "*", 0, 0).getTotalMatchesLong())
                .isZero();
        assertThat(search("folders", "dc:title contains \"pop\"", "*", 0, 0).getTotalMatchesLong()).isEqualTo(2);
    }

    @Test
    void itemsUseTheBrowseMappingAndFilter() throws Exception {
        DIDLContent full = parse(search("0", "upnp:artist contains \"cafe\"", "*", 0, 1));
        var item = full.getItems().get(0);
        assertThat(item.getId()).isEqualTo(ObjectIds.track(first));
        assertThat(item.getResources()).hasSize(1);
        assertThat(item.getFirstPropertyValue(
                org.jupnp.support.model.DIDLObject.Property.UPNP.ALBUM.class)).isEqualTo("Album");

        DIDLContent slim = parse(search("0", "upnp:artist contains \"cafe\"", "dc:title", 0, 1));
        assertThat(slim.getItems().get(0).getResources()).isEmpty();
        assertThat(slim.getItems().get(0).getFirstPropertyValue(
                org.jupnp.support.model.DIDLObject.Property.UPNP.ALBUM.class)).isNull();
    }

    @Test
    void sortCriteriaOrderTheHitsBeforePaging() throws Exception {
        String crit = "dc:title contains \"love\" and upnp:class derivedfrom \"object.item\"";
        java.util.function.Function<String, List<String>> titles = didl -> {
            List<String> out = new java.util.ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<dc:title>([^<]*)</dc:title>").matcher(didl);
            while (m.find()) {
                out.add(m.group(1));
            }
            return out;
        };
        List<String> asc = titles.apply(service.search("0", crit, "*", 0, 0, SortCriterion.valueOf("+dc:title")).getResult());
        List<String> desc = titles.apply(service.search("0", crit, "*", 0, 0, SortCriterion.valueOf("-dc:title")).getResult());
        assertThat(asc).hasSizeGreaterThan(1).isSorted();
        assertThat(desc).isEqualTo(new java.util.ArrayList<>(asc).reversed());
        assertThat(service.search("0", crit, "*", 0, 0, new SortCriterion[0]).getResult()).isNotEmpty();
    }

    @Test
    void star_returnsEverythingBelowTheScope() throws Exception {
        assertThat(search("0", "*", "*", 0, 0).getTotalMatchesLong()).isEqualTo(3 + 26);
        assertThat(search(ObjectIds.folder(rock), "*", "*", 0, 0).getTotalMatchesLong()).isEqualTo(25);
    }

    @Test
    void malformedCriteriaGiveError708() {
        for (String bad : new String[] { "", "dc:title contains", "((", "dc:title contains \"x", "garbage here" }) {
            assertThatThrownBy(() -> search("0", bad, "*", 0, 0)).isInstanceOf(ContentDirectoryException.class)
                    .satisfies(e -> assertThat(((ContentDirectoryException) e).getErrorCode()).isEqualTo(708));
        }
    }

    @Test
    void unknownOrNonContainerScopeGivesError710() {
        for (String bad : new String[] { "f:nope", "nope", "t:" + first.id(), "" }) {
            assertThatThrownBy(() -> search(bad, "*", "*", 0, 0)).isInstanceOf(ContentDirectoryException.class)
                    .satisfies(e -> assertThat(((ContentDirectoryException) e).getErrorCode()).isEqualTo(710));
        }
    }

    @Test
    void searchCapabilitiesStillAdvertised() {
        assertThat(service.getSearchCapabilities().toString())
                .isEqualTo("dc:title,upnp:artist,upnp:album,upnp:genre,dc:creator,upnp:author,upnp:class");
    }
}
