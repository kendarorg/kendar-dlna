package org.kendar.dlna.catalog;

import java.util.List;

import org.kendar.dlna.shared.Normalizer;

/**
 * A track with the folder's {@code album} overlay applied to the album-level fields (album, album artist, year,
 * genre, disc). Per-track fields (title, track number, artist) always come from the tags.
 * {@code albumArtists} and {@code genres} are the multi-value forms of the album artist and genre (a single
 * overlay value replaces them).
 */
public record EffectiveTrack(Track track, String album, String albumArtist, Integer year, String genre,
        Integer discNo, Integer discTotal, List<String> albumArtists, List<String> genres) {

    public static EffectiveTrack of(Track track, DirectorySettings settings) {
        AlbumInfo o = settings.album();
        TrackTags t = track.tags();
        return new EffectiveTrack(track,
                o.title() != null ? o.title() : t.album(),
                o.albumArtist() != null ? o.albumArtist() : t.albumArtist(),
                o.year() != null ? o.year() : t.year(),
                o.genre() != null ? o.genre() : t.genre(),
                o.discNumber() != null ? o.discNumber() : t.discNo(),
                o.discTotal() != null ? o.discTotal() : t.discTotal(),
                o.albumArtist() != null ? List.of(o.albumArtist()) : t.allAlbumArtists(),
                o.genre() != null ? List.of(o.genre()) : t.allGenres());
    }

    /** Track artists (multi-value aware); per-track, never overlaid. */
    public List<String> artists() {
        return track.tags().allArtists();
    }

    /** Track composers (multi-value aware); per-track, never overlaid. */
    public List<String> composers() {
        return track.tags().allComposers();
    }

    /**
     * True when the overlay sets a field and at least one track's tag differs from it (the UI flags such
     * directories as out of sync). Tracks without the tag count as differing.
     */
    public static boolean outOfSync(DirectorySettings settings, List<Track> tracks) {
        AlbumInfo o = settings.album();
        for (Track tr : tracks) {
            TrackTags t = tr.tags();
            if (differs(o.title(), t.album()) || differs(o.albumArtist(), t.albumArtist())
                    || differs(o.genre(), t.genre()) || differs(o.year(), t.year())
                    || differs(o.discNumber(), t.discNo()) || differs(o.discTotal(), t.discTotal())) {
                return true;
            }
        }
        return false;
    }

    private static boolean differs(String overlay, String tag) {
        return overlay != null && !Normalizer.normalize(overlay).equals(Normalizer.normalize(tag));
    }

    private static boolean differs(Integer overlay, Integer tag) {
        return overlay != null && !overlay.equals(tag);
    }
}
