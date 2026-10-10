package org.kendar.dlna.delivery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.kendar.dlna.catalog.ContainerNavigation;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.shared.RootRef;

/**
 * Pure request logic of the stream endpoint (no Jetty): path parsing, catalog lookup, file checks, variant
 * selection (original, MP3, LPCM), planning with the exact Content-Length of the chosen form.
 */
public final class StreamResolver {

    private static final Logger LOG = Logger.getLogger(StreamResolver.class.getName());

    /**
     * {@code body} is non-null only when the plan has a body to send; {@code lease} (never null) must be closed
     * when the body has been sent, it keeps the context (e.g. a Random N snapshot) alive meanwhile.
     */
    public record Resolved(StreamPlan plan, StreamBody body, ContainerNavigation.Lease lease) {
        public Resolved(StreamPlan plan, StreamBody body) {
            this(plan, body, ContainerNavigation.Lease.NONE);
        }

        /** The backing file of a plain-file body (the original, or a cached transcode), else null. */
        public Path file() {
            return body == null ? null : body.file();
        }
    }

    private final Supplier<Snapshot> snapshot;
    private final MediaFiles files;
    private final Consumer<RootRef> onMissing;
    private final ContainerNavigation navigation;
    private final TranscodeService transcoding;
    private final Prefetcher prefetcher;

    /**
     * @param onMissing called when a catalogued file no longer exists on disk (404 is returned; the catalog
     *                  removal is wired by the composition root)
     */
    public StreamResolver(Supplier<Snapshot> snapshot, MediaFiles files, Consumer<RootRef> onMissing) {
        this(snapshot, files, onMissing, ContainerNavigation.NONE);
    }

    /**
     * @param navigation owner of the non-folder contexts (playlists, Random N, views); a URL whose context is
     *                   virtual (contains a dot) but unknown to it answers 404
     */
    public StreamResolver(Supplier<Snapshot> snapshot, MediaFiles files, Consumer<RootRef> onMissing,
            ContainerNavigation navigation) {
        this(snapshot, files, onMissing, navigation, null, null);
    }

    /**
     * @param transcoding null = original files only (transcoded variants answer 404)
     * @param prefetcher  null = no prefetch
     */
    public StreamResolver(Supplier<Snapshot> snapshot, MediaFiles files, Consumer<RootRef> onMissing,
            ContainerNavigation navigation, TranscodeService transcoding, Prefetcher prefetcher) {
        this.snapshot = snapshot;
        this.files = files;
        this.onMissing = onMissing;
        this.navigation = navigation;
        this.transcoding = transcoding;
        this.prefetcher = prefetcher;
    }

    /** The item after {@code itemId} in the browsing context (no wrap-around); used by prefetch. */
    public Optional<Track> nextInContext(String containerId, String itemId) {
        return navigation.next(containerId, itemId);
    }

    public Resolved resolve(String method, String pathInContext, String rangeHeader) {
        return resolve(method, pathInContext, rangeHeader, "unknown");
    }

    /**
     * @param pathInContext e.g. {@code /<containerId>/<itemId>[/<variant>]}
     * @param clientKey     identifies the client (its address) for prefetch bookkeeping
     */
    public Resolved resolve(String method, String pathInContext, String rangeHeader, String clientKey) {
        String[] parts = pathInContext == null ? new String[0] : pathInContext.replaceFirst("^/+", "").split("/");
        if (parts.length < 2 || parts.length > 3 || parts[0].isEmpty() || parts[1].isEmpty()) {
            return status(404);
        }
        Optional<StreamVariant> parsed = StreamVariant.parse(parts.length == 3 ? parts[2] : null);
        if (parsed.isEmpty()) {
            return status(404);
        }
        StreamVariant variant = parsed.get();
        boolean virtual = parts[0].indexOf('.') >= 0;
        if (virtual && !navigation.knows(parts[0])) {
            return status(404);   // expired or unknown Random N snapshot, view group or playlist
        }
        Optional<Track> found = snapshot.get().trackById(parts[1]);
        if (found.isEmpty()) {
            return status(404);
        }
        Track track = found.get();
        if (track.isSegment() && !variant.transcoded()) {
            variant = StreamVariant.mp3(0);   // a cue / chapter track only exists decoded: never a byte range of the file
        }
        if (variant.transcoded() ? transcoding == null || !transcoding.canTranscode(track.format())
                : track.format() == Format.MIDI) {
            return status(404);   // not offered: no transcoder, or MIDI which only exists rendered
        }
        Path file = files.resolve(track.ref());
        StreamBody body;
        try {
            if (file == null || !Files.isRegularFile(file)) {
                onMissing.accept(track.ref());
                return status(404);
            }
            body = variant.transcoded() ? transcoding.body(track, file, variant)
                    : new StreamBody.FileBody(file, Files.size(file), MediaTypes.mimeType(track.format()),
                            MediaTypes.contentFeatures(track.format()));
        } catch (java.nio.file.NoSuchFileException e) {
            onMissing.accept(track.ref());
            return status(404);
        } catch (IOException e) {
            if (variant.transcoded()) {
                LOG.warning("Cannot transcode " + track.ref() + " (" + variant.path() + "): " + e);
                return status(500);
            }
            onMissing.accept(track.ref());
            return status(404);
        }
        StreamPlan plan = StreamPlan.of(method, rangeHeader, body.length(), body.mimeType(), body.contentFeatures());
        boolean send = plan.sendBody() && plan.length() > 0;
        if (send && prefetcher != null) {
            prefetcher.streamStarted(clientKey, parts[0], track, variant);
        }
        return new Resolved(plan, send ? body : null,
                send && virtual ? navigation.streamStarted(parts[0]) : ContainerNavigation.Lease.NONE);
    }

    private static Resolved status(int code) {
        return new Resolved(new StreamPlan(code, Map.of(), 0, 0, false), null);
    }
}
