package org.kendar.dlna.search;

/** Searchable fields (plan section 9). {@link #ANY} matches when any of the other fields matches. */
public enum Field {
    /** Track: tag title or file name (without extension). Folder: display title or directory name. */
    TITLE,
    /** Track artist or, when absent, the album artist. */
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    GENRE,
    /** Track composer(s). */
    COMPOSER,
    /** Full file name, tracks only. */
    FILENAME,
    /** Directory name: the folder itself, or the parent directory of a track. */
    DIRECTORY,
    ANY
}
