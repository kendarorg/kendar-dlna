package org.kendar.dlna.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;

class AbcIngestionTest {

    @TempDir
    Path tmp;

    @Test
    void aFileWithSeveralTunesBecomesAVirtualAlbumAndASingleTuneStaysAFile() throws Exception {
        Path music = Files.createDirectories(tmp.resolve("music"));
        Files.writeString(music.resolve("book.abc"), "X:1\nT:One\nC:Anon\nL:1/4\nK:C\nCDEF|\n\nX:2\nT:Two\nL:1/4\nK:G\nGABc|\n");
        Files.writeString(music.resolve("single.abc"), "X:1\nT:Solo\nL:1/4\nK:C\nCDEF|\n");
        Map<String, Path> roots = new LinkedHashMap<>();
        roots.put("m", music);
        Snapshot s = new LibraryScanner(new AbcTagReader(true, "")).scan(roots, 1);

        assertThat(s.unsupported()).isEmpty();
        assertThat(s.tracks()).extracting(t -> t.ref().rel())
                .containsExactlyInAnyOrder("book.abc#1", "book.abc#2", "single.abc");
        Track two = s.tracks().stream().filter(t -> t.ref().rel().equals("book.abc#2")).findFirst().orElseThrow();
        assertThat(two.format()).isEqualTo(Format.ABC);
        assertThat(two.isSegment()).isTrue();
        assertThat(two.segment().index()).isEqualTo(2);
        assertThat(two.tags().title()).isEqualTo("Two");
        assertThat(two.tags().album()).isEqualTo("book");
        assertThat(two.tags().trackNo()).isEqualTo(2);
        Track one = s.tracks().stream().filter(t -> t.ref().rel().equals("book.abc#1")).findFirst().orElseThrow();
        assertThat(one.tags().artist()).isEqualTo("Anon");
        Track solo = s.tracks().stream().filter(t -> t.ref().rel().equals("single.abc")).findFirst().orElseThrow();
        assertThat(solo.isSegment()).isFalse();
        assertThat(solo.tags().title()).isEqualTo("Solo");
        assertThat(solo.durationMillis()).isEqualTo(2000);
    }

    @Test
    void abcIsUnsupportedWithTheReasonWhenRenderingIsUnavailable() throws Exception {
        AbcTagReader off = new AbcTagReader(false, "no SoundFont configured");
        assertThat(off.supports(Format.ABC)).isFalse();
        assertThat(off.owns(Format.ABC)).isTrue();
        assertThat(off.unsupportedReason(Format.ABC)).contains("no SoundFont configured");
    }
}
