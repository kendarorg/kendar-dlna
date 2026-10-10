package org.kendar.dlna.ingestion;

import java.util.ArrayList;
import java.util.List;

import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Segment;
import org.kendar.dlna.catalog.SortMode;
import org.kendar.dlna.catalog.Split;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.ingestion.CueSheet.CueFile;
import org.kendar.dlna.ingestion.CueSheet.CueTrack;
import org.kendar.dlna.shared.RootRef;

/**
 * Turns one audio file plus the sections that describe it (a cue sheet entry or embedded chapters) into a virtual
 * album: a {@link Folder} at the file's own ref carrying a {@link Split} marker, and one {@link Track} per section
 * at {@code <file ref>#<n>} (n = position, from 1) with a {@link Segment}. A file with fewer than two sections is
 * not split ({@link Result#tracks()} empty); a single cue track then only supplies tags for the whole file
 * ({@link Result#wholeTags()}).
 */
public final class VirtualAlbums {

    private VirtualAlbums() {
    }

    /** The file being split. */
    public record Source(RootRef ref, long mtime, long size, long addedAt, Format format, TrackMeta meta) {
    }

    /** {@code folder} and {@code tracks} are null / empty when the file is not split. */
    public record Result(Folder folder, List<Track> tracks, TrackTags wholeTags, List<String> warnings) {
        public boolean virtual() {
            return folder != null;
        }
    }

    /**
     * @param origin   {@code cue}, {@code tag-cue} or {@code chapters}
     * @param hash     see {@link Split#hash()}
     * @param cueFile  name of the .cue file, or null
     * @param list     the directory's list.json (null = none)
     * @param parent   the directory holding the file (its album overlay is inherited)
     */
    public static Result build(Source src, CueSheet sheet, CueFile entry, String origin, String hash, String cueFile,
            ListJson.Result list, Folder parent) {
        List<String> warnings = new ArrayList<>(sheet.warnings());
        TrackMeta meta = src.meta();
        int rate = meta.sampleRate();
        long total = meta.sampleCount() > 0 ? meta.sampleCount()
                : rate > 0 && meta.durationMillis() > 0 ? Math.round(meta.durationMillis() * rate / 1000.0) : 0;
        List<CueTrack> cues = entry.tracks();
        String fileName = src.ref().name();
        if (cues.isEmpty()) {
            return new Result(null, List.of(), null, warnings);
        }
        if (cues.size() == 1 && cues.get(0).startMicros() == 0) {
            return new Result(null, List.of(), tags(sheet, cues.get(0), meta.tags(), fileName, 0, null), warnings);
        }
        if (cues.size() < 2 && cues.get(0).startMicros() < 1_000_000L) {
            return new Result(null, List.of(), null, warnings);
        }
        if (rate <= 0 || total <= 0) {
            warnings.add("cannot split " + fileName + ": its length or sample rate is unknown");
            return new Result(null, List.of(), null, warnings);
        }
        TrackTags base = meta.tags();
        String albumTitle = firstNonBlank(sheet.title(), base.album(), base.title(), baseName(fileName));
        String albumArtist = firstNonBlank(sheet.performer(), base.albumArtist(), base.artist());
        ListJson.Entry fileEntry = list == null ? null : list.find(fileName).orElse(null);
        if (fileEntry != null && fileEntry.partOf() != null) {
            albumTitle = fileEntry.partOf();
        }
        if (fileEntry != null && fileEntry.author() != null) {
            albumArtist = fileEntry.author();
        }

        List<Track> tracks = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < cues.size(); i++) {
            CueTrack c = cues.get(i);
            long startSample = Math.round(c.startMicros() * (double) rate / 1_000_000d);
            if (startSample >= total) {
                warnings.add("track " + c.number() + " starts after the end of " + fileName + ", skipped");
                continue;
            }
            long endSample = total;
            if (i + 1 < cues.size()) {
                long next = Math.round(cues.get(i + 1).startMicros() * (double) rate / 1_000_000d);
                endSample = Math.min(total, next);
            }
            if (endSample <= startSample) {
                continue;
            }
            n++;
            long startMs = Math.round(startSample * 1000.0 / rate);
            long endMs = Math.round(endSample * 1000.0 / rate);
            Segment seg = new Segment(n, startMs, endMs, startSample, endSample, origin);
            TrackTags tags = tags(sheet, c, base, fileName, n, albumTitle);
            tags = withAlbum(tags, albumTitle, albumArtist);
            ListJson.Entry te = list == null ? null : list.findSection(fileName, n, startMs).orElse(null);
            tags = ListJson.apply(tags, te);
            tracks.add(new Track(src.ref().parent().child(fileName + "#" + n), src.mtime(), src.size(), src.format(), tags,
                    endMs - startMs, meta.bitrateKbps(), rate, meta.channels(), meta.embeddedArt(),
                    endSample - startSample, 0, src.addedAt(), seg));
        }
        if (tracks.size() < 2) {
            warnings.add(fileName + " has fewer than two usable sections, shown as a single file");
            return new Result(null, List.of(), null, warnings);
        }
        DirectorySettings settings = settingsFor(parent, albumTitle);
        Folder folder = new Folder(src.ref(), src.mtime(), settings, null, parent != null && parent.hasFront(), false,
                null, null, new Split(origin, hash, src.mtime(), src.size(), cueFile, warnings));
        return new Result(folder, tracks, null, warnings);
    }

    /** The settings of a virtual album: sorted by track number, the parent's album overlay, titled by the album. */
    public static DirectorySettings settingsFor(Folder parent, String albumTitle) {
        DirectorySettings p = parent == null ? DirectorySettings.DEFAULT : parent.settings();
        return new DirectorySettings(SortMode.TRACK, List.of(), p.album(), 0, List.of(), 0, albumTitle);
    }

    /** An old virtual album carried over to a rescan: parent-derived fields (front art, overlay) are refreshed. */
    public static Folder refresh(Folder old, Folder parent) {
        return refresh(old, parent, old.settings().displayName());
    }

    /** Like {@link #refresh(Folder, Folder)} with the album title (display name) replaced. */
    public static Folder refresh(Folder old, Folder parent, String albumTitle) {
        return new Folder(old.ref(), old.mtime(), settingsFor(parent, albumTitle), null,
                parent != null && parent.hasFront(), false, null, null, old.split());
    }

    private static TrackTags tags(CueSheet sheet, CueTrack c, TrackTags base, String fileName, int position,
            String albumTitle) {
        String title = c.title() != null ? c.title()
                : position > 0 ? String.format("Track %02d", c.number())
                : base.title();
        String artist = firstNonBlank(c.performer(), sheet.performer(), base.artist());
        String album = firstNonBlank(albumTitle, sheet.title(), base.album());
        String albumArtist = firstNonBlank(sheet.performer(), base.albumArtist());
        String composer = firstNonBlank(c.songwriter(), sheet.songwriter(), base.composer());
        Integer year = sheet.year() != null ? sheet.year() : base.year();
        String genre = firstNonBlank(sheet.genre(), base.genre());
        Integer disc = sheet.discNumber() != null ? sheet.discNumber() : base.discNo();
        return new TrackTags(title, artist, album, albumArtist, genre, year, c.number(), disc, base.discTotal(),
                List.of(), List.of(), List.of(), composer, List.of(), base.transcriber());
    }

    private static TrackTags withAlbum(TrackTags t, String album, String albumArtist) {
        return new TrackTags(t.title(), t.artist(), album, albumArtist, t.genre(), t.year(), t.trackNo(), t.discNo(),
                t.discTotal(), t.artists(), List.of(), t.genres(), t.composer(), t.composers(), t.transcriber());
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }
}
