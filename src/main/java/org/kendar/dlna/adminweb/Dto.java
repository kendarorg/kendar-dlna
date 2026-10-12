package org.kendar.dlna.adminweb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.kendar.dlna.catalog.AlbumInfo;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.DisplayNames;
import org.kendar.dlna.catalog.EffectiveTrack;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;

/** JSON shapes of the REST resources (plain maps, so the wire format is explicit). */
final class Dto {

    private Dto() {
    }

    static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    static Map<String, Object> settings(DirectorySettings s) {
        AlbumInfo a = s.album();
        List<String> views = new ArrayList<>();
        s.views().forEach(v -> views.add(v.name().toLowerCase(Locale.ROOT)));
        return obj("sort", s.sort().name().toLowerCase(Locale.ROOT), "order", s.order(),
                "album", obj("title", a.title(), "albumArtist", a.albumArtist(), "year", a.year(), "genre", a.genre(),
                        "discNumber", a.discNumber(), "discTotal", a.discTotal()),
                "random", s.random(), "recent", s.recent(), "views", views, "displayName", s.displayName(),
                "keepWholeFiles", s.keepWholeFiles(), "bucketThreshold", s.bucketThreshold(), "hidden", s.hidden());
    }

    /** A track with its tags; {@code parent} (nullable) is the settings of its directory for the sync marker. */
    static Map<String, Object> track(Track t, DirectorySettings parent) {
        TrackTags g = t.tags();
        boolean outOfSync = parent != null && EffectiveTrack.outOfSync(parent, List.of(t));
        return obj("path", t.ref().qualified(), "name", t.fileName(), "displayTitle", DisplayNames.trackTitle(t),
                "title", g.title(), "artist", g.artist(), "album", g.album(), "albumArtist", g.albumArtist(),
                "genre", g.genre(), "composer", g.composer(), "transcriber", g.transcriber(), "year", g.year(), "trackNo", g.trackNo(), "discNo", g.discNo(),
                "discTotal", g.discTotal(), "format", t.format().name().toLowerCase(Locale.ROOT),
                "editable", t.format().tagsEditable() && !t.isSegment(),
                "editableFields", t.isSegment() ? List.of() : t.format().editableFields(),
                "segment", segment(t),
                "durationMillis", t.durationMillis(),
                "bitrateKbps", t.bitrateKbps(), "sampleRate", t.sampleRate(), "channels", t.channels(),
                "embeddedArt", t.embeddedArt(), "size", t.size(), "mtime", t.mtime(), "outOfSync", outOfSync);
    }

    /** The cue sheet / chapter range of a virtual track (null for a normal track). */
    static Map<String, Object> segment(Track t) {
        if (t.segment() == null) {
            return null;
        }
        return obj("source", t.fileRef().qualified(), "index", t.segment().index(), "origin", t.segment().origin(),
                "startMillis", t.segment().startMillis(), "endMillis", t.segment().endMillis());
    }

    static Map<String, Object> folderRef(Folder f) {
        return obj("path", f.ref().qualified(), "name", f.ref().name(), "displayName", DisplayNames.folderTitle(f),
                "virtual", f.isVirtual(), "hidden", f.settings().hidden());
    }
}
