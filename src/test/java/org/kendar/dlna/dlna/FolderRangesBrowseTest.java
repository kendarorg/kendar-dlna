package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.jupnp.support.contentdirectory.DIDLParser;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.item.Item;
import org.junit.jupiter.api.Test;
import org.kendar.dlna.artwork.ArtworkService;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.SortMode;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.delivery.DefaultStreamUrlBuilder;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shuffle.Shuffle;
import org.kendar.dlna.testutil.MutableClock;
import org.kendar.dlna.views.Views;

class FolderRangesBrowseTest {

    private static final ClientId VLC = new ClientId("10.0.0.2", "VLC");

    private BrowseEngine engine(List<Folder> folders, List<Track> tracks) {
        Snapshot snap = new Snapshot(11, Instant.EPOCH, folders, tracks, List.<UnsupportedFile>of(), List.of());
        SnapshotHolder holder = new SnapshotHolder(snap);
        return new BrowseEngine(holder, new DefaultStreamUrlBuilder(), () -> "http://x:8200",
                new ArtworkService(holder, ref -> null, ref -> Optional.empty(), Path.of("unused")),
                new Shuffle(holder::current, new MutableClock(), Duration.ofMinutes(30), new Random(5)),
                new Views(holder::current, 500));
    }

    private static DIDLContent children(BrowseEngine e, String id) throws Exception {
        BrowseResult r = e.browse(id, BrowseFlag.DIRECT_CHILDREN, "*", 0, 0, VLC);
        return new DIDLParser().parse(r.getResult());
    }

    private static List<Track> songs(String dir, int n) {
        List<Track> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String title = String.format("Song %03d", i);
            out.add(track(dir + "/" + title + ".mp3", new TrackTags(title, null, null, null, null, null, null, null, null), 0));
        }
        return out;
    }

    @Test
    void aLongNameSortedDirectoryIsListedThroughTenRanges() throws Exception {
        Folder lib = folder("lib");
        BrowseEngine e = engine(List.of(lib), songs("lib", 120));

        DIDLContent top = children(e, "f:" + lib.id());

        assertThat(top.getItems()).isEmpty();
        assertThat(top.getContainers()).hasSize(10);
        assertThat(top.getContainers()).allSatisfy(c -> {
            assertThat(c.getId()).startsWith("c:g." + lib.id() + ".t");
            assertThat(c.getParentID()).isEqualTo("f:" + lib.id());
        });
        int total = 0;
        for (Container range : top.getContainers()) {
            DIDLContent inner = children(e, range.getId());
            assertThat(inner.getItems()).isNotEmpty();
            assertThat(inner.getItems()).allSatisfy(i -> assertThat(i.getParentID()).isEqualTo(range.getId()));
            assertThat(range.getChildCount()).isEqualTo(inner.getItems().size());
            total += inner.getItems().size();
        }
        assertThat(total).isEqualTo(120);
        BrowseResult meta = e.browse("f:" + lib.id(), BrowseFlag.METADATA, "*", 0, 0, VLC);
        assertThat(new DIDLParser().parse(meta.getResult()).getContainers().get(0).getChildCount()).isEqualTo(10);
    }

    @Test
    void shortOrNotNameSortedDirectoriesStayFlat() throws Exception {
        Folder small = folder("small");
        Folder byTrack = folder("bytrack", new DirectorySettings(SortMode.TRACK, null, null, 0, null));
        List<Track> all = new ArrayList<>(songs("small", 40));
        all.addAll(songs("bytrack", 120));
        BrowseEngine e = engine(List.of(small, byTrack), all);

        assertThat(children(e, "f:" + small.id()).getItems()).hasSize(40);
        assertThat(children(e, "f:" + byTrack.id()).getContainers()).isEmpty();
        assertThat(children(e, "f:" + byTrack.id()).getItems()).hasSize(120);
    }

    @Test
    void foldersAndTracksAreGroupedIndependentlyWithFoldersFirst() throws Exception {
        Folder lib = folder("lib");
        List<Folder> folders = new ArrayList<>(List.of(lib));
        for (int i = 0; i < 30; i++) {
            folders.add(folder(String.format("lib/Dir %02d", i)));
        }
        BrowseEngine e = engine(folders, songs("lib", 40));

        DIDLContent top = children(e, "f:" + lib.id());

        // 30 folders and 40 tracks: neither kind alone exceeds 50, so nothing is grouped
        assertThat(top.getContainers()).hasSize(30);
        assertThat(top.getItems()).hasSize(40);

        BrowseEngine big = engine(folders, songs("lib", 90));
        DIDLContent grouped = children(big, "f:" + lib.id());
        assertThat(grouped.getContainers()).as("30 real folders + 10 track ranges").hasSize(40);
        assertThat(grouped.getContainers().stream().filter(c -> c.getId().startsWith("f:")).count()).isEqualTo(30);
        assertThat(grouped.getContainers().stream().filter(c -> c.getId().startsWith("c:g.")).count()).isEqualTo(10);
        assertThat(grouped.getItems()).isEmpty();
    }

    @Test
    void rangeIdsAreStableAcrossBrowses() throws Exception {
        Folder lib = folder("lib");
        BrowseEngine e = engine(List.of(lib), songs("lib", 120));
        List<String> a = children(e, "f:" + lib.id()).getContainers().stream().map(Container::getId).toList();
        List<String> b = children(e, "f:" + lib.id()).getContainers().stream().map(Container::getId).toList();
        assertThat(a).isEqualTo(b);
        // an item inside a range plays with the directory as its stream context
        Item first = children(e, a.get(0)).getItems().get(0);
        assertThat(first.getFirstResource().getValue()).contains("/stream/").contains(lib.id());
    }
}
