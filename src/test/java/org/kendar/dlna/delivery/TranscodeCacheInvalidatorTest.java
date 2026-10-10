package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kendar.dlna.testutil.Fixtures.folder;
import static org.kendar.dlna.testutil.Fixtures.snapshot;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.catalog.ChangeSet;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.SnapshotHolder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.shared.RootRef;

class TranscodeCacheInvalidatorTest {

    @TempDir
    Path dir;

    private static Track track(String name, long mtime, long size) {
        return new Track(RootRef.parse("m/" + name), mtime, size, Format.FLAC, TrackTags.EMPTY, 1000, 0, 44100, 2,
                false);
    }

    @Test
    void changedAndRemovedFilesLoseTheirCachedTranscodesAndOthersKeepTheirs() throws Exception {
        TranscodeCache cache = new TranscodeCache(dir, 1 << 20, Duration.ofHours(1), Clock.systemUTC());
        Track same = track("same.flac", 1, 100);
        Track changed = track("changed.flac", 1, 100);
        Track gone = track("gone.flac", 1, 100);
        for (Track t : List.of(same, changed, gone)) {
            cache.getOrCreate(TranscodeCache.key(t.ref()), new TranscodeCache.Source(t.ref().qualified(), t.mtime(),
                    t.size(), "mp3"), (out, c) -> out.write(1), () -> false);
        }
        Snapshot now = snapshot(List.of(folder("m")), List.of(same, track("changed.flac", 2, 100)));
        SnapshotHolder holder = new SnapshotHolder(now);

        new TranscodeCacheInvalidator(cache, holder::current).changed(new ChangeSet(8, Set.of("k")));

        assertThat(cache.contains(TranscodeCache.key(same.ref()))).isTrue();
        assertThat(cache.contains(TranscodeCache.key(changed.ref()))).isFalse();
        assertThat(cache.contains(TranscodeCache.key(gone.ref()))).isFalse();
    }
}
