package org.kendar.dlna.lyrics;

import java.io.IOException;
import java.util.List;

/** A source of lyrics (an online service). More providers are added by implementing this and registering it. */
public interface LyricsProvider {

    /** What to look for. {@code durationSeconds} is 0 when unknown. */
    record Query(String artist, String title, String album, int durationSeconds) {
    }

    /** The id used in {@code lyrics.provider} of config.json. */
    String id();

    /**
     * The candidates for the query, best first (empty = nothing found). Synced lyrics are preferred over plain ones.
     *
     * @throws IOException the service is unreachable or answered with an error
     */
    List<Lyrics> find(Query query) throws IOException;
}
