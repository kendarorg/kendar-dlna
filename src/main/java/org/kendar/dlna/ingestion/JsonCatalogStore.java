package org.kendar.dlna.ingestion;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.kendar.dlna.catalog.CatalogStore;
import org.kendar.dlna.catalog.DirectorySettings;
import org.kendar.dlna.catalog.Folder;
import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.catalog.Playlist;
import org.kendar.dlna.catalog.Segment;
import org.kendar.dlna.catalog.Split;
import org.kendar.dlna.catalog.Snapshot;
import org.kendar.dlna.catalog.Track;
import org.kendar.dlna.catalog.TrackTags;
import org.kendar.dlna.catalog.UnsupportedFile;
import org.kendar.dlna.shared.RootRef;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The persisted index (plan section 10): the full catalog as one JSON file, written with a streaming generator to
 * a temp file and moved into place atomically, read back with a streaming parser. Optionally gzip (reading detects
 * it by itself). Derived data (sort orders, lookup maps, views) is rebuilt by the {@link Snapshot} constructor;
 * object ids are stable hashes of the paths, so they are not stored.
 *
 * <p>{@link #load()} returns empty (never throws) for a missing, corrupt, outdated or foreign index (other
 * library roots), which makes the caller do a full scan.
 */
public final class JsonCatalogStore implements CatalogStore {

    /**
     * 2: tracks carry {@code addedAt} and the composer tags (an older index triggers a full scan). The list.json
     * fields (folder hash, track transcriber) were added without a bump: a missing hash makes the next scan re-read
     * the tags of that directory.
     */
    public static final int FORMAT_VERSION = 2;
    public static final String FILE_NAME = "catalog.json";

    private static final Logger LOG = Logger.getLogger(JsonCatalogStore.class.getName());

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY,
                    JsonInclude.Include.NON_EMPTY))
            .build();

    /** The file header; it comes first so a foreign or outdated index is rejected before parsing the rest. */
    record Header(int formatVersion, String appVersion, String rootsFingerprint, long writtenAt, long lastScanAt,
            long systemUpdateId) {
    }

    record FolderDto(String path, long mtime, DirectorySettings settings, String settingsError, boolean hasFront,
            boolean hasRear, String listHash, String listError, Split split) {
    }

    record TrackDto(String path, long mtime, long size, Format format, TrackTags tags, long durationMillis,
            int bitrateKbps, int sampleRate, int channels, boolean embeddedArt, long sampleCount,
            long renderedMillis, long addedAt, Segment segment) {
    }

    record UnsupportedDto(String path, long size, String reason) {
    }

    private final Path file;
    private final String rootsFingerprint;
    private final String appVersion;
    private final boolean gzip;
    private final Clock clock;

    public JsonCatalogStore(Path dataDir, String rootsFingerprint, String appVersion, boolean gzip) {
        this(dataDir, rootsFingerprint, appVersion, gzip, Clock.systemUTC());
    }

    public JsonCatalogStore(Path dataDir, String rootsFingerprint, String appVersion, boolean gzip, Clock clock) {
        this.file = dataDir.resolve(FILE_NAME);
        this.rootsFingerprint = rootsFingerprint;
        this.appVersion = appVersion;
        this.gzip = gzip;
        this.clock = clock;
    }

    public Path file() {
        return file;
    }

    @Override
    public synchronized void save(Snapshot s) {
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
                    OutputStream out = gzip ? new GZIPOutputStream(raw, 1 << 16) : raw) {
                write(s, out);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            LOG.warning("Cannot write the catalog index " + file + ": " + e);
        }
    }

    private void write(Snapshot s, OutputStream out) throws IOException {
        try (JsonGenerator g = MAPPER.getFactory().createGenerator(out)) {
            g.setCodec(MAPPER);
            g.writeStartObject();
            g.writeFieldName("header");
            g.writeObject(new Header(FORMAT_VERSION, appVersion, rootsFingerprint, clock.millis(),
                    s.scannedAt().toEpochMilli(), s.updateId()));
            g.writeFieldName("containerUpdateIds");
            g.writeObject(new TreeMap<>(s.containerUpdateIds()));
            g.writeArrayFieldStart("folders");
            for (Folder f : s.allFolders()) {
                g.writeObject(new FolderDto(f.ref().qualified(), f.mtime(),
                        f.settings().equals(DirectorySettings.DEFAULT) ? null : f.settings(), f.settingsError(),
                        f.hasFront(), f.hasRear(), f.listHash(), f.listError(), f.split()));
            }
            g.writeEndArray();
            g.writeArrayFieldStart("tracks");
            for (Track t : s.tracks()) {
                g.writeObject(new TrackDto(t.ref().qualified(), t.mtime(), t.size(), t.format(), t.tags(),
                        t.durationMillis(), t.bitrateKbps(), t.sampleRate(), t.channels(), t.embeddedArt(),
                        t.sampleCount(), t.renderedMillis(), t.addedAt(), t.segment()));
            }
            g.writeEndArray();
            g.writeArrayFieldStart("unsupported");
            for (UnsupportedFile u : s.unsupported()) {
                g.writeObject(new UnsupportedDto(u.ref().qualified(), u.size(), u.reason()));
            }
            g.writeEndArray();
            g.writeArrayFieldStart("playlists");
            for (Playlist p : s.playlistDefinitions()) {
                g.writeObject(p);
            }
            g.writeEndArray();
            g.writeEndObject();
        }
    }

    @Override
    public synchronized Optional<Snapshot> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            raw.mark(2);
            int b0 = raw.read();
            int b1 = raw.read();
            raw.reset();
            boolean gz = b0 == 0x1f && b1 == 0x8b;
            try (InputStream in = gz ? new GZIPInputStream(raw, 1 << 16) : raw) {
                return read(in);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning("Catalog index " + file + " is unreadable, a full scan is needed: " + e);
            return Optional.empty();
        }
    }

    private Optional<Snapshot> read(InputStream in) throws IOException {
        try (JsonParser p = MAPPER.getFactory().createParser(in)) {
            p.setCodec(MAPPER);
            if (p.nextToken() != JsonToken.START_OBJECT || p.nextToken() != JsonToken.FIELD_NAME
                    || !"header".equals(p.currentName())) {
                LOG.warning("Catalog index has no header, a full scan is needed");
                return Optional.empty();
            }
            p.nextToken();
            Header h = p.readValueAs(Header.class);
            if (h.formatVersion() != FORMAT_VERSION) {
                LOG.info("Catalog index format " + h.formatVersion() + " != " + FORMAT_VERSION
                        + ", full scan needed");
                return Optional.empty();
            }
            if (!rootsFingerprint.equals(h.rootsFingerprint())) {
                LOG.info("Library roots changed since the catalog index was written, full scan needed");
                return Optional.empty();
            }
            Map<String, Long> ids = new LinkedHashMap<>();
            List<Folder> folders = new ArrayList<>();
            List<Track> tracks = new ArrayList<>();
            List<UnsupportedFile> unsupported = new ArrayList<>();
            List<Playlist> playlists = new ArrayList<>();
            boolean complete = false;
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                p.nextToken();
                switch (name) {
                    case "containerUpdateIds" -> {
                        while (p.nextToken() == JsonToken.FIELD_NAME) {
                            String key = p.currentName();
                            p.nextToken();
                            ids.put(key, p.getLongValue());
                        }
                    }
                    case "folders" -> {
                        while (p.nextToken() == JsonToken.START_OBJECT) {
                            FolderDto d = p.readValueAs(FolderDto.class);
                            folders.add(new Folder(RootRef.parse(d.path()), d.mtime(), d.settings(),
                                    d.settingsError(), d.hasFront(), d.hasRear(), d.listHash(), d.listError(), d.split()));
                        }
                    }
                    case "tracks" -> {
                        while (p.nextToken() == JsonToken.START_OBJECT) {
                            TrackDto d = p.readValueAs(TrackDto.class);
                            tracks.add(new Track(RootRef.parse(d.path()), d.mtime(), d.size(), d.format(),
                                    d.tags(), d.durationMillis(), d.bitrateKbps(), d.sampleRate(), d.channels(),
                                    d.embeddedArt(), d.sampleCount(), d.renderedMillis(), d.addedAt(), d.segment()));
                        }
                    }
                    case "unsupported" -> {
                        while (p.nextToken() == JsonToken.START_OBJECT) {
                            UnsupportedDto d = p.readValueAs(UnsupportedDto.class);
                            unsupported.add(new UnsupportedFile(RootRef.parse(d.path()), d.size(), d.reason()));
                        }
                    }
                    case "playlists" -> {
                        while (p.nextToken() == JsonToken.START_OBJECT) {
                            playlists.add(p.readValueAs(Playlist.class));
                        }
                        complete = true;   // written last: a truncated file never gets here
                    }
                    default -> p.skipChildren();
                }
            }
            if (!complete || p.currentToken() != JsonToken.END_OBJECT) {
                LOG.warning("Catalog index is truncated, a full scan is needed");
                return Optional.empty();
            }
            return Optional.of(new Snapshot(h.systemUpdateId(), Instant.ofEpochMilli(h.lastScanAt()), folders,
                    tracks, unsupported, playlists, ids));
        }
    }
}
