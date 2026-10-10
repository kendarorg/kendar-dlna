package org.kendar.dlna.delivery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.kendar.dlna.catalog.Track;

/** What the prefetcher needs from the transcoding side. */
public interface PrefetchTarget {

    /** Whether the form of the track must be produced and is not cached yet. */
    boolean needsPrefetch(Track track, StreamVariant variant);

    /** Produces the form into the cache; stops (throwing) when {@code cancelled} turns true. */
    void prefetch(Track track, Path file, StreamVariant variant, BooleanSupplier cancelled) throws IOException;
}
