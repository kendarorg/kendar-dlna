package org.kendar.dlna.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.tags;
import static org.kendar.dlna.testutil.Fixtures.track;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.kendar.dlna.shared.RootRef;

class ChildSorterTest {

    private static DirectorySettings sort(SortMode mode, String... order) {
        return new DirectorySettings(mode, List.of(order), AlbumInfo.NONE, 0, List.of());
    }

    private static List<String> names(ChildSorter.Result r) {
        return r.children().stream().map(c -> c.ref().name()).toList();
    }

    @Test
    void filenameModePutsSubdirsFirstWithNaturalOrder() {
        List<Folder> fs = List.of(folder("m/Disc 10"), folder("m/disc 2"));
        List<Track> ts = List.of(track("m/Track 10.mp3"), track("m/track 2.mp3"), track("m/À first.mp3"));

        ChildSorter.Result r = ChildSorter.sort(sort(SortMode.FILENAME), fs, ts);

        assertThat(names(r)).containsExactly("disc 2", "Disc 10", "À first.mp3", "track 2.mp3", "Track 10.mp3");
        assertThat(r.missing()).isEmpty();
    }

    @Test
    void titleModeUsesTagTitleAndAlbumTitleOfSubdirs() {
        Folder a = folder("m/zzz", new DirectorySettings(null, null,
                new AlbumInfo("Alpha", null, null, null, null, null), 0, null));
        Folder b = folder("m/bbb");
        List<Track> ts = List.of(track("m/1.mp3", tags("Zulu", null, null), 0),
                track("m/2.mp3", tags("alpha", null, null), 0), track("m/Beta.mp3"));

        ChildSorter.Result r = ChildSorter.sort(sort(SortMode.TITLE), List.of(b, a), ts);

        assertThat(names(r)).containsExactly("zzz", "bbb", "2.mp3", "Beta.mp3", "1.mp3");
    }

    @Test
    void titleModeSortsSubdirsByTheirDisplayName() {
        Folder a = folder("m/aaa", new DirectorySettings(null, null, null, 0, null, 0, "Zeta"));
        Folder b = folder("m/bbb", new DirectorySettings(null, null, null, 0, null, 0, "Alpha"));
        Folder c = folder("m/ccc");

        ChildSorter.Result r = ChildSorter.sort(sort(SortMode.TITLE), List.of(a, b, c), List.of());

        assertThat(names(r)).containsExactly("bbb", "ccc", "aaa");
        // filename mode still uses the real names
        assertThat(names(ChildSorter.sort(sort(SortMode.FILENAME), List.of(a, b, c), List.of())))
                .containsExactly("aaa", "bbb", "ccc");
    }

    @Test
    void trackModeOrdersByDiscThenTrackThenName() {
        List<Track> ts = List.of(
                track("m/c.mp3", tags("c", 2, 1), 0),
                track("m/b.mp3", tags("b", 1, 10), 0),
                track("m/a.mp3", tags("a", 1, 2), 0),
                track("m/untagged.mp3"));

        ChildSorter.Result r = ChildSorter.sort(sort(SortMode.TRACK), List.of(), ts);

        assertThat(names(r)).containsExactly("a.mp3", "b.mp3", "c.mp3", "untagged.mp3");
    }

    @Test
    void mtimeModeOldestFirstForFilesAndDirs() {
        Folder old = new Folder(RootRef.parse("m/zold"), 10, null, null, false, false);
        Folder recent = new Folder(RootRef.parse("m/arecent"), 99, null, null, false, false);
        List<Track> ts = List.of(track("m/a.mp3", TrackTags.EMPTY, 50), track("m/b.mp3", TrackTags.EMPTY, 5));

        ChildSorter.Result r = ChildSorter.sort(sort(SortMode.MTIME), List.of(recent, old), ts);

        assertThat(names(r)).containsExactly("zold", "arecent", "b.mp3", "a.mp3");
    }

    @Test
    void explicitInterleavesAppendsUnlistedAndReportsMissing() {
        List<Folder> fs = List.of(folder("m/sub"), folder("m/extraDir"));
        List<Track> ts = List.of(track("m/a.mp3"), track("m/b.mp3"), track("m/extra.mp3"));

        ChildSorter.Result r = ChildSorter.sort(
                sort(SortMode.EXPLICIT, "b.mp3", "sub", "ghost.mp3", "a.mp3", "b.mp3"), fs, ts);

        assertThat(names(r)).containsExactly("b.mp3", "sub", "a.mp3", "extraDir", "extra.mp3");
        assertThat(r.missing()).containsExactly("ghost.mp3");
    }
}
