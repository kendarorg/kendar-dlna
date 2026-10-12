package org.kendar.dlna.platform;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.kendar.dlna.clients.ClientsConfig;

/** Typed view of {@code config.json}. Sections for later milestones are ignored for now. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppConfig(Library library, Network network, Server server, Random random, Views views,
        Scan scan, Transcode transcode, ClientsConfig clients) {

    public static final String DEFAULT_FRIENDLY_NAME = "Kendar DLNA";
    public static final int DEFAULT_PORT = 8200;

    public static final int DEFAULT_RANDOM_TTL_MINUTES = 30;
    public static final int DEFAULT_BUCKET_THRESHOLD = 500;

    public static final int DEFAULT_MP3_BITRATE = 320;
    public static final int DEFAULT_CACHE_MB = 2048;
    public static final int DEFAULT_CACHE_TTL_HOURS = 24 * 14;

    public static final String DEFAULT_SCAN_CRON = "0 */12 * * *";
    public static final int DEFAULT_CATCH_UP_HOURS = 12;

    public AppConfig(Library library, Network network, Server server) {
        this(library, network, server, null, null, null, null, null);
    }

    public AppConfig(Library library, Network network, Server server, Random random, Views views) {
        this(library, network, server, random, views, null, null, null);
    }

    public AppConfig {
        library = library == null ? new Library(null, null, null) : library;
        network = network == null ? new Network(null, null) : network;
        server = server == null ? new Server(null) : server;
        random = random == null ? new Random(null) : random;
        views = views == null ? new Views(null) : views;
        scan = scan == null ? new Scan(null, null) : scan;
        transcode = transcode == null ? new Transcode(null, null, null, null, null, null, null, null, null)
                : transcode;
        clients = clients == null ? ClientsConfig.defaults() : clients;
    }

    /** Roots map name to a path (inside {@code /music} in Docker); {@code mainRoot} = default root of new users (default: the first). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Library(Map<String, String> roots, String playlistsDir, String dataDir, String mainRoot) {
        public Library(Map<String, String> roots, String playlistsDir, String dataDir) {
            this(roots, playlistsDir, dataDir, null);
        }

        public Library {
            roots = roots == null ? new LinkedHashMap<>() : roots;
            playlistsDir = playlistsDir == null ? "/playlists" : playlistsDir;
            dataDir = dataDir == null ? "/data" : dataDir;
            mainRoot = mainRoot == null || mainRoot.isBlank() ? null : mainRoot.strip();
        }

        /** The default root of new users: the configured {@code mainRoot}, else the first root (null without roots). */
        public String effectiveMainRoot() {
            if (mainRoot != null) {
                return mainRoot;
            }
            return roots.isEmpty() ? null : roots.keySet().iterator().next();
        }
    }

    /**
     * {@code advertiseAddress}: IP to advertise (null = auto-detect). {@code port}: the single HTTP port.
     * {@code lanCidrs}: address ranges counted as LAN for the LAN-only paths (admin, DLNA); empty = loopback and the
     * private ranges. {@code trustedProxies}: reverse proxies whose X-Forwarded-* headers are honored (default none).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Network(String advertiseAddress, Integer port, java.util.List<String> lanCidrs,
            java.util.List<String> trustedProxies) {
        public Network(String advertiseAddress, Integer port) {
            this(advertiseAddress, port, null, null);
        }

        public Network {
            port = port == null ? DEFAULT_PORT : port;
            lanCidrs = lanCidrs == null ? java.util.List.of() : java.util.List.copyOf(lanCidrs);
            trustedProxies = trustedProxies == null ? java.util.List.of() : java.util.List.copyOf(trustedProxies);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Server(String friendlyName) {
        public Server {
            friendlyName = friendlyName == null ? DEFAULT_FRIENDLY_NAME : friendlyName;
        }
    }

    /** Random N settings: {@code ttlMinutes} = snapshot lifetime after the last activity. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Random(Integer ttlMinutes) {
        public Random {
            ttlMinutes = ttlMinutes == null ? DEFAULT_RANDOM_TTL_MINUTES : ttlMinutes;
        }
    }

    /** Views settings: lists longer than {@code bucketThreshold} are split into A-Z / # containers. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Views(Integer bucketThreshold) {
        public Views {
            bucketThreshold = bucketThreshold == null ? DEFAULT_BUCKET_THRESHOLD : bucketThreshold;
        }
    }

    /**
     * Rescan settings: {@code cron} = 5-field cron expression of the periodic rescan (default every 12 h);
     * {@code catchUpThresholdHours} = a background scan runs right after a fast start when the last completed
     * scan is older than this.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scan(String cron, Integer catchUpThresholdHours) {
        public Scan {
            cron = cron == null ? DEFAULT_SCAN_CRON : cron;
            catchUpThresholdHours = catchUpThresholdHours == null ? DEFAULT_CATCH_UP_HOURS : catchUpThresholdHours;
        }
    }

    /**
     * Transcoding (plan sections 11 and 14).
     *
     * @param enabled               false offers originals only (MIDI then needs transcoding and is unsupported)
     * @param bitrateKbps           MP3 bitrate (CBR); 32..320, an MPEG-1 Layer III rate
     * @param prefetch              encode the next track ahead of time
     * @param prefetchJobs          size of the low-priority prefetch pool (1 or 2)
     * @param cacheMaxMb            size cap of the transcode cache in `<dataDir>/transcode`
     * @param cacheTtlHours        entries unused for this long are dropped
     * @param soundFontPath         SF2 file for MIDI rendering; null = the bundled GeneralUser GS if present
     * @param midiSilenceThreshold  absolute 16-bit amplitude regarded as silence when trimming the tail (0..2000)
     * @param midiMaxTailSeconds    longest tail rendered after the last MIDI event (0..120)
     * @param ffmpegPath           the ffmpeg binary for WMA and APE (a name looked up on the PATH, or a path); null = "ffmpeg"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transcode(Boolean enabled, Integer bitrateKbps, Boolean prefetch, Integer prefetchJobs,
            Integer cacheMaxMb, Integer cacheTtlHours, String soundFontPath, Integer midiSilenceThreshold,
            Integer midiMaxTailSeconds, String ffmpegPath) {

        /** Without an ffmpeg location of its own (looked up on the PATH). */
        public Transcode(Boolean enabled, Integer bitrateKbps, Boolean prefetch, Integer prefetchJobs,
                Integer cacheMaxMb, Integer cacheTtlHours, String soundFontPath, Integer midiSilenceThreshold,
                Integer midiMaxTailSeconds) {
            this(enabled, bitrateKbps, prefetch, prefetchJobs, cacheMaxMb, cacheTtlHours, soundFontPath,
                    midiSilenceThreshold, midiMaxTailSeconds, null);
        }

        public Transcode {
            ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? null : ffmpegPath.strip();
            enabled = enabled == null ? Boolean.TRUE : enabled;
            bitrateKbps = bitrateKbps == null ? DEFAULT_MP3_BITRATE : bitrateKbps;
            prefetch = prefetch == null ? Boolean.TRUE : prefetch;
            prefetchJobs = prefetchJobs == null ? 1 : prefetchJobs;
            cacheMaxMb = cacheMaxMb == null ? DEFAULT_CACHE_MB : cacheMaxMb;
            cacheTtlHours = cacheTtlHours == null ? DEFAULT_CACHE_TTL_HOURS : cacheTtlHours;
            soundFontPath = soundFontPath == null || soundFontPath.isBlank() ? null : soundFontPath.strip();
            midiSilenceThreshold = midiSilenceThreshold == null ? 16 : midiSilenceThreshold;
            midiMaxTailSeconds = midiMaxTailSeconds == null ? 10 : midiMaxTailSeconds;
        }
    }

    /**
     * Copy in which relative paths of the file (library roots, playlists dir, data dir, SoundFont) are resolved
     * against {@code baseDir}, the directory holding {@code config.json}. Absolute paths are left unchanged.
     */
    AppConfig resolvedAgainst(java.nio.file.Path baseDir) {
        java.nio.file.Path base = baseDir.toAbsolutePath().normalize();
        Map<String, String> roots = new LinkedHashMap<>();
        library.roots().forEach((name, dir) -> roots.put(name, dir == null || dir.isBlank() ? dir : resolve(base, dir)));
        Library lib = new Library(roots, resolve(base, library.playlistsDir()), resolve(base, library.dataDir()),
                library.mainRoot());
        Transcode t = transcode;
        Transcode tc = new Transcode(t.enabled(), t.bitrateKbps(), t.prefetch(), t.prefetchJobs(), t.cacheMaxMb(),
                t.cacheTtlHours(), t.soundFontPath() == null ? null : resolve(base, t.soundFontPath()),
                t.midiSilenceThreshold(), t.midiMaxTailSeconds(),
                t.ffmpegPath() != null && (t.ffmpegPath().contains("/") || t.ffmpegPath().contains("\\"))
                        ? resolve(base, t.ffmpegPath()) : t.ffmpegPath());
        return new AppConfig(lib, network, server, random, views, scan, tc, clients);
    }

    static String resolve(java.nio.file.Path base, String path) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String p = path.strip();
        // rooted paths ("/music") stay as written, also on Windows where they are not "absolute"
        if (p.startsWith("/") || p.startsWith("\\") || java.nio.file.Path.of(p).isAbsolute()) {
            return p;
        }
        return base.resolve(p).normalize().toString();
    }

    /** Copy with the ffmpeg location replaced (null = unchanged). */
    AppConfig withFfmpegPath(String path) {
        if (path == null) {
            return this;
        }
        Transcode t = transcode;
        return new AppConfig(library, network, server, random, views, scan, new Transcode(t.enabled(), t.bitrateKbps(),
                t.prefetch(), t.prefetchJobs(), t.cacheMaxMb(), t.cacheTtlHours(), t.soundFontPath(),
                t.midiSilenceThreshold(), t.midiMaxTailSeconds(), path), clients);
    }

    /** Copy with overridden values (used for env / system property overrides). */
    AppConfig with(String dataDir, Integer port, String friendlyName) {
        return new AppConfig(
                new Library(library.roots(), library.playlistsDir(), dataDir != null ? dataDir : library.dataDir(),
                        library.mainRoot()),
                new Network(network.advertiseAddress(), port != null ? port : network.port(), network.lanCidrs(),
                        network.trustedProxies()),
                new Server(friendlyName != null ? friendlyName : server.friendlyName()), random, views, scan, transcode,
                clients);
    }
}
