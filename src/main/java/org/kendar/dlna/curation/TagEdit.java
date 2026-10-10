package org.kendar.dlna.curation;

import java.util.ArrayList;
import java.util.List;

/**
 * A change to apply to the tags of one or many files. Per field: {@code null} = leave unchanged, empty string =
 * clear the field, otherwise the new value. Numeric fields hold digits only.
 */
public record TagEdit(String title, String artist, String album, String albumArtist, String genre, String year,
        String trackNo, String discNo, String discTotal, String composer) {

    private static final int MAX_TEXT = 500;

    public TagEdit {
        title = clean(title);
        artist = clean(artist);
        album = clean(album);
        albumArtist = clean(albumArtist);
        genre = clean(genre);
        year = clean(year);
        trackNo = clean(trackNo);
        discNo = clean(discNo);
        discTotal = clean(discTotal);
        composer = clean(composer);
    }

    /** An edit without a composer change. */
    public TagEdit(String title, String artist, String album, String albumArtist, String genre, String year,
            String trackNo, String discNo, String discTotal) {
        this(title, artist, album, albumArtist, genre, year, trackNo, discNo, discTotal, null);
    }

    private static String clean(String s) {
        return s == null ? null : s.strip();
    }

    public boolean isEmpty() {
        return title == null && artist == null && album == null && albumArtist == null && genre == null
                && year == null && trackNo == null && discNo == null && discTotal == null && composer == null;
    }

    /** The same edit with every field not named in {@code fields} left unchanged (null). */
    public TagEdit restrictedTo(java.util.Collection<String> fields) {
        return new TagEdit(keep(fields, "title", title), keep(fields, "artist", artist), keep(fields, "album", album),
                keep(fields, "albumArtist", albumArtist), keep(fields, "genre", genre), keep(fields, "year", year),
                keep(fields, "trackNo", trackNo), keep(fields, "discNo", discNo), keep(fields, "discTotal", discTotal),
                keep(fields, "composer", composer));
    }

    private static String keep(java.util.Collection<String> fields, String name, String value) {
        return fields.contains(name) ? value : null;
    }

    /** Problems with the values (empty = valid). */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        text("title", title, out);
        text("artist", artist, out);
        text("album", album, out);
        text("albumArtist", albumArtist, out);
        text("genre", genre, out);
        text("composer", composer, out);
        number("year", year, 9999, out);
        number("trackNo", trackNo, 9999, out);
        number("discNo", discNo, 999, out);
        number("discTotal", discTotal, 999, out);
        return out;
    }

    private static void text(String name, String v, List<String> out) {
        if (v == null) {
            return;
        }
        if (v.length() > MAX_TEXT) {
            out.add(name + " is longer than " + MAX_TEXT + " characters");
        }
        if (v.chars().anyMatch(Character::isISOControl)) {
            out.add(name + " contains control characters");
        }
    }

    private static void number(String name, String v, int max, List<String> out) {
        if (v == null || v.isEmpty()) {
            return;
        }
        if (!v.matches("\\d{1,4}") || Integer.parseInt(v) > max) {
            out.add(name + " must be a number between 0 and " + max);
        }
    }
}
