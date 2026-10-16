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

    /** The ids of the providers behind this one (a chain lists them all), in order. */
    default List<String> ids() {
        return List.of(id());
    }

    /** Told what each provider found, as soon as it answers; {@code error} is null unless it failed. */
    interface Listener {
        void answered(String providerId, List<Lyrics> found, String error);
    }

    /** Same as {@link #find(Query)}, telling the listener about each provider's answer when it arrives. */
    default List<Lyrics> find(Query query, Listener listener) throws IOException {
        try {
            List<Lyrics> found = find(query);
            listener.answered(id(), found, null);
            return found;
        } catch (IOException e) {
            listener.answered(id(), List.of(), e.getMessage());
            throw e;
        }
    }

    /**
     * The candidates for the query, best first (empty = nothing found). Synced lyrics are preferred over plain ones.
     *
     * @throws IOException the service is unreachable or answered with an error
     */
    List<Lyrics> find(Query query) throws IOException;
}
