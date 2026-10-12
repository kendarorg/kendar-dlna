package org.kendar.dlna.ingestion;

import java.util.ArrayList;
import java.util.List;

import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Segment;
import org.kendar.dlna.catalog.Split;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;

/**
 * Turns an ABC file with several tunes into a virtual album, like {@link VirtualAlbums} does for cue sheets: a
 * {@link Folder} at the file's ref carrying a {@link Split} marker, and one {@link Track} per tune at
 * {@code <file ref>#<position>} with a {@link Segment} (origin {@code abc}, no sample positions: a tune is rendered
 * on its own, not cut out of an audio stream). A file with fewer than two tunes is not split.
 */
public final class AbcAlbums {

    private AbcAlbums() {
    }

    public static final String ORIGIN = "abc";

    public static VirtualAlbums.Result build(VirtualAlbums.Source src, List<AbcFile.Tune> tunes, ListJson.Result list,
            Folder parent) {
        if (tunes.size() < 2) {
            return new VirtualAlbums.Result(null, List.of(), null, List.of());
        }
        String fileName = src.ref().name();
        String albumTitle = tunes.get(0).book() != null ? tunes.get(0).book() : baseName(fileName);
        ListJson.Entry fileEntry = list == null ? null : list.find(fileName).orElse(null);
        if (fileEntry != null && fileEntry.partOf() != null) {
            albumTitle = fileEntry.partOf();
        }
        List<Track> tracks = new ArrayList<>();
        long at = 0;
        for (AbcFile.Tune t : tunes) {
            TrackTags base = AbcTagReader.tags(t);
            String title = t.title() != null ? t.title() : "Tune " + t.number();
            TrackTags tags = new TrackTags(title, base.artist(), albumTitle, null, null, null, t.position(), null,
                    null, List.of(), List.of(), List.of(), base.composer(), List.of(), base.transcriber());
            long endMs = at + t.durationMillis();
            ListJson.Entry te = list == null ? null : list.findSection(fileName, t.position(), at).orElse(null);
            tags = ListJson.apply(tags, te);
            Segment seg = new Segment(t.position(), at, endMs, 0, 0, ORIGIN);
            tracks.add(new Track(src.ref().parent().child(fileName + "#" + t.position()), src.mtime(), src.size(),
                    src.format(), tags, t.durationMillis(), 0, 0, 0, false, 0, 0, src.addedAt(), seg));
            at = endMs;
        }
        DirectorySettings settings = VirtualAlbums.settingsFor(parent, albumTitle);
        Folder folder = new Folder(src.ref(), src.mtime(), settings, null, parent != null && parent.hasFront(), false,
                null, null, new Split(ORIGIN, "abc:" + tunes.size(), src.mtime(), src.size(), null, List.of()));
        return new VirtualAlbums.Result(folder, tracks, null, List.of());
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
