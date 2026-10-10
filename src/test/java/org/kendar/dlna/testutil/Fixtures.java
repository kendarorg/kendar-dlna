package org.kendar.dlna.testutil;

import java.time.Instant;
import java.util.List;

import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.shared.RootRef;

/** Small builders for catalog objects in tests. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Folder folder(String qualified) {
        return folder(qualified, DirectorySettings.DEFAULT);
    }

    public static Folder folder(String qualified, DirectorySettings settings) {
        return new Folder(RootRef.parse(qualified), 0, settings, null, false, false);
    }

    public static Track track(String qualified) {
        return track(qualified, TrackTags.EMPTY, 0);
    }

    public static Track track(String qualified, TrackTags tags, long mtime) {
        Format f = Format.fromFileName(qualified).orElse(Format.MP3);
        return new Track(RootRef.parse(qualified), mtime, 1000, f, tags, 61_500, 320, 44100, 2, false);
    }

    public static TrackTags tags(String title, Integer disc, Integer no) {
        return new TrackTags(title, null, null, null, null, null, no, disc, null);
    }

    public static Snapshot snapshot(List<Folder> folders, List<Track> tracks) {
        return new Snapshot(7, Instant.EPOCH, folders, tracks, List.<UnsupportedFile>of());
    }
}
