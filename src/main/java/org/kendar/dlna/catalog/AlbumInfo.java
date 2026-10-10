package org.kendar.dlna.catalog;

/**
 * Album-level overlay from settings.json. Non-null fields override the corresponding tag values in all output.
 */
public record AlbumInfo(String title, String albumArtist, Integer year, String genre, Integer discNumber,
        Integer discTotal) {

    public static final AlbumInfo NONE = new AlbumInfo(null, null, null, null, null, null);

    public AlbumInfo {
        title = blankToNull(title);
        albumArtist = blankToNull(albumArtist);
        genre = blankToNull(genre);
    }

    public boolean isEmpty() {
        return title == null && albumArtist == null && year == null && genre == null && discNumber == null
                && discTotal == null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
