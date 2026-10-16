package org.kendar.dlna.catalog;

import java.util.List;

/**
 * Per-track metadata read from the file tags. Any single-value field may be null. The list fields hold the
 * individual values of multi-value tags (ID3v2.4, Vorbis comments); they are empty when the tag has none, in which
 * case the single-value field is the only value. {@code transcriber} comes from a directory's {@code list.json}.
 */
public record TrackTags(String title, String artist, String album, String albumArtist, String genre, Integer year,
        Integer trackNo, Integer discNo, Integer discTotal, List<String> artists, List<String> albumArtists,
        List<String> genres, String composer, List<String> composers, String transcriber,
        java.util.Map<String, List<String>> extras) {

    /** Tags without extra tags. */
    public TrackTags(String title, String artist, String album, String albumArtist, String genre, Integer year,
            Integer trackNo, Integer discNo, Integer discTotal, List<String> artists, List<String> albumArtists,
            List<String> genres, String composer, List<String> composers, String transcriber) {
        this(title, artist, album, albumArtist, genre, year, trackNo, discNo, discTotal, artists, albumArtists, genres,
                composer, composers, transcriber, null);
    }

    public static final TrackTags EMPTY = new TrackTags(null, null, null, null, null, null, null, null, null);

    public TrackTags {
        artists = artists == null ? List.of() : List.copyOf(artists);
        albumArtists = albumArtists == null ? List.of() : List.copyOf(albumArtists);
        genres = genres == null ? List.of() : List.copyOf(genres);
        composers = composers == null ? List.of() : List.copyOf(composers);
        extras = normalizeExtras(extras);
    }

    /** Tags without a transcriber. */
    public TrackTags(String title, String artist, String album, String albumArtist, String genre, Integer year,
            Integer trackNo, Integer discNo, Integer discTotal, List<String> artists, List<String> albumArtists,
            List<String> genres, String composer, List<String> composers) {
        this(title, artist, album, albumArtist, genre, year, trackNo, discNo, discTotal, artists, albumArtists,
                genres, composer, composers, null);
    }

    /** Tags without a composer. */
    public TrackTags(String title, String artist, String album, String albumArtist, String genre, Integer year,
            Integer trackNo, Integer discNo, Integer discTotal, List<String> artists, List<String> albumArtists,
            List<String> genres) {
        this(title, artist, album, albumArtist, genre, year, trackNo, discNo, discTotal, artists, albumArtists,
                genres, null, null);
    }

    /** Tags without multi-value lists and without a composer. */
    public TrackTags(String title, String artist, String album, String albumArtist, String genre, Integer year,
            Integer trackNo, Integer discNo, Integer discTotal) {
        this(title, artist, album, albumArtist, genre, year, trackNo, discNo, discTotal, null, null, null, null,
                null);
    }

    /** The values of an extra tag (configured in extraTags, read from the file), by lower-case name. */
    public List<String> extra(String tag) {
        List<String> v = tag == null ? null : extras.get(tag.strip().toLowerCase(java.util.Locale.ROOT));
        return v == null ? List.of() : v;
    }

    /** The same tags with other extra tags. */
    public TrackTags withExtras(java.util.Map<String, List<String>> next) {
        return new TrackTags(title, artist, album, albumArtist, genre, year, trackNo, discNo, discTotal, artists,
                albumArtists, genres, composer, composers, transcriber, next);
    }

    private static java.util.Map<String, List<String>> normalizeExtras(java.util.Map<String, List<String>> in) {
        if (in == null || in.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, List<String>> out = new java.util.TreeMap<>();
        in.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null && !v.isEmpty()) {
                out.put(k.strip().toLowerCase(java.util.Locale.ROOT), List.copyOf(v));
            }
        });
        return java.util.Collections.unmodifiableMap(out);
    }

    /** All track artists: the multi-values when present, else the single value, else empty. */
    public List<String> allArtists() {
        return values(artists, artist);
    }

    public List<String> allAlbumArtists() {
        return values(albumArtists, albumArtist);
    }

    public List<String> allGenres() {
        return values(genres, genre);
    }

    /** All composers: the multi-values when present, else the single value, else empty. */
    public List<String> allComposers() {
        return values(composers, composer);
    }

    private static List<String> values(List<String> multi, String single) {
        if (!multi.isEmpty()) {
            return multi;
        }
        return single == null || single.isBlank() ? List.of() : List.of(single);
    }
}
