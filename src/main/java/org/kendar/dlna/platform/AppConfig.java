package org.kendar.dlna.platform;

import java.util.LinkedHashMap;
import java.util.List;
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
     * @param ffmpegPath           the ffmpeg binary for WMA, APE, DSD, WavPack, MPC, m4b, HE-AAC and the FLAC / ALAC
     *                             output (a name looked up on the PATH, or a path); null = "ffmpeg"
     * @param output                sample size, rate cap and DSD handling of the PCM based outputs
     * @param rules                 ordered transcoding rules, the first match wins (see {@link Rule})
     * @param passthrough           formats whose virtual tracks (cue / chapter / ABC sections) are served as a cut of
     *                              the original file instead of being re-encoded: "wav" (exact) and "mp3" (frame
     *                              granular); default only "wav"
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transcode(Boolean enabled, Integer bitrateKbps, Boolean prefetch, Integer prefetchJobs,
            Integer cacheMaxMb, Integer cacheTtlHours, String soundFontPath, Integer midiSilenceThreshold,
            Integer midiMaxTailSeconds, String ffmpegPath, Output output, List<Rule> rules, List<String> passthrough) {

        /** Without an ffmpeg location, output settings, rules or passthrough of its own. */
        public Transcode(Boolean enabled, Integer bitrateKbps, Boolean prefetch, Integer prefetchJobs,
                Integer cacheMaxMb, Integer cacheTtlHours, String soundFontPath, Integer midiSilenceThreshold,
                Integer midiMaxTailSeconds, String ffmpegPath) {
            this(enabled, bitrateKbps, prefetch, prefetchJobs, cacheMaxMb, cacheTtlHours, soundFontPath,
                    midiSilenceThreshold, midiMaxTailSeconds, ffmpegPath, null, null, null);
        }

        /** Without an ffmpeg location of its own (looked up on the PATH). */
        public Transcode(Boolean enabled, Integer bitrateKbps, Boolean prefetch, Integer prefetchJobs,
                Integer cacheMaxMb, Integer cacheTtlHours, String soundFontPath, Integer midiSilenceThreshold,
                Integer midiMaxTailSeconds) {
            this(enabled, bitrateKbps, prefetch, prefetchJobs, cacheMaxMb, cacheTtlHours, soundFontPath,
                    midiSilenceThreshold, midiMaxTailSeconds, null, null, null, null);
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
            output = output == null ? new Output(null, null, null, null) : output;
            rules = rules == null ? List.of() : List.copyOf(rules);
            passthrough = passthrough == null ? List.of("wav")
                    : passthrough.stream().map(p -> p.strip().toLowerCase(java.util.Locale.ROOT)).toList();
        }

        /** Copy with the SoundFont and ffmpeg paths replaced. */
        Transcode withPaths(String soundFont, String ffmpeg) {
            return new Transcode(enabled, bitrateKbps, prefetch, prefetchJobs, cacheMaxMb, cacheTtlHours, soundFont,
                    midiSilenceThreshold, midiMaxTailSeconds, ffmpeg, output, rules, passthrough);
        }
    }

    /**
     * Output controls of the PCM based forms (LPCM, WAV, FLAC, ALAC) and of DSD.
     *
     * @param maxSampleRate sources faster than this are resampled down to it; 0 = keep the source rate
     * @param bitDepth      16 or 24 bit samples
     * @param dsdPcmRate    rate DSD is decimated to: 44100, 88200 or 176400 (the 48 kHz family scales along)
     * @param dsdMode       "pcm" (decimated) or "dop" (DSD over PCM: 24-bit LPCM, WAV or FLAC only)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Output(Integer maxSampleRate, Integer bitDepth, Integer dsdPcmRate, String dsdMode) {
        public Output {
            maxSampleRate = maxSampleRate == null ? 0 : maxSampleRate;
            bitDepth = bitDepth == null ? 16 : bitDepth;
            dsdPcmRate = dsdPcmRate == null ? 44_100 : dsdPcmRate;
            dsdMode = dsdMode == null || dsdMode.isBlank() ? "pcm" : dsdMode.strip().toLowerCase(java.util.Locale.ROOT);
        }

        public boolean dop() {
            return "dop".equals(dsdMode);
        }
    }

    /**
     * One transcoding rule: all given conditions must hold, and the first matching rule decides what a track is
     * offered as.
     *
     * @param formats              source formats (flac, wav, dsd, ...); empty = any
     * @param profiles             client profile ids; empty = any (this is how a rule is limited to a device)
     * @param minSampleRate        source sample rate at least this; 0 = no condition
     * @param maxSampleRate        source sample rate at most this; 0 = no condition
     * @param minBitrateKbps       source bitrate at least this; 0 = no condition
     * @param maxBitrateKbps       source bitrate at most this; 0 = no condition
     * @param action               "native" (the original only), or the form to offer: "mp3", "lpcm", "wav", "flac", "alac"
     * @param bitrateKbps          MP3 bitrate for "mp3"; 0 = the default
     * @param outputMaxSampleRate  sample rate cap of the PCM based forms; null = the output default
     * @param outputBitDepth       bit depth of the PCM based forms; null = the output default
     * @param hideOriginal         also drop the original from the offers
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rule(List<String> formats, List<String> profiles, Integer minSampleRate, Integer maxSampleRate,
            Integer minBitrateKbps, Integer maxBitrateKbps, String action, Integer bitrateKbps,
            Integer outputMaxSampleRate, Integer outputBitDepth, Boolean hideOriginal) {
        public Rule {
            formats = formats == null ? List.of()
                    : formats.stream().map(f -> f.strip().toUpperCase(java.util.Locale.ROOT)).toList();
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
            minSampleRate = minSampleRate == null ? 0 : minSampleRate;
            maxSampleRate = maxSampleRate == null ? 0 : maxSampleRate;
            minBitrateKbps = minBitrateKbps == null ? 0 : minBitrateKbps;
            maxBitrateKbps = maxBitrateKbps == null ? 0 : maxBitrateKbps;
            action = action == null ? "" : action.strip().toLowerCase(java.util.Locale.ROOT);
            bitrateKbps = bitrateKbps == null ? 0 : bitrateKbps;
            hideOriginal = hideOriginal != null && hideOriginal;
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
        Transcode tc = t.withPaths(t.soundFontPath() == null ? null : resolve(base, t.soundFontPath()),
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
        return new AppConfig(library, network, server, random, views, scan, transcode.withPaths(transcode.soundFontPath(), path),
                clients);
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
