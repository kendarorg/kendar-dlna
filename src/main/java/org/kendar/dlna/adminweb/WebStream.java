package org.kendar.dlna.adminweb;

import java.util.function.Supplier;

import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.delivery.ActiveStreams;
import org.kendar.dlna.delivery.MediaFiles;
import org.kendar.dlna.delivery.Prefetcher;
import org.kendar.dlna.delivery.StreamResolver;
import org.kendar.dlna.delivery.TranscodeService;

/**
 * What the {@code /api/v1/stream} group needs from the delivery core: the catalog, the files, the shared
 * {@link StreamResolver} (so web and DLNA share planning, transcoding and the cache), the transcode service and
 * the prefetcher.
 *
 * @param transcoding null = originals only
 * @param prefetcher  null = prefetch hints are accepted but do nothing
 * @param active      null = web streams are not listed in the admin view
 */
public record WebStream(Supplier<Snapshot> snapshot, MediaFiles files, StreamResolver resolver,
        TranscodeService transcoding, Prefetcher prefetcher, ActiveStreams active, int maxTranscodesPerUser) {

    /** Concurrent transcodes per user (plan section 7). */
    public static final int DEFAULT_MAX_TRANSCODES_PER_USER = StreamResources.MAX_TRANSCODES_PER_USER;

    public WebStream(Supplier<Snapshot> snapshot, MediaFiles files, StreamResolver resolver,
            TranscodeService transcoding, Prefetcher prefetcher, ActiveStreams active) {
        this(snapshot, files, resolver, transcoding, prefetcher, active, DEFAULT_MAX_TRANSCODES_PER_USER);
    }

    StreamResources resources() {
        return new StreamResources(snapshot, files, resolver, transcoding, prefetcher, active,
                maxTranscodesPerUser);
    }
}
