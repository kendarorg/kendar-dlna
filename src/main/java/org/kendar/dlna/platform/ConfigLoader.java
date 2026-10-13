package org.kendar.dlna.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

import org.kendar.dlna.shared.Json;

/**
 * Loads {@code config.json} from the config directory, writing a template when missing.
 *
 * <p>Overrides (system property first, then environment variable):
 * <ul>
 *   <li>{@code kendar.configDir} / {@code KENDAR_CONFIG_DIR} (default {@code /config})</li>
 *   <li>{@code kendar.dataDir} / {@code KENDAR_DATA_DIR}</li>
 *   <li>{@code kendar.port} / {@code KENDAR_PORT}</li>
 *   <li>{@code kendar.friendlyName} / {@code KENDAR_FRIENDLY_NAME}</li>
 *   <li>{@code kendar.ffmpegPath} / {@code KENDAR_FFMPEG_PATH}: the ffmpeg binary used for WMA and APE</li>
 *   <li>{@code kendar.defaultRoot} / {@code KENDAR_DEFAULT_ROOT}: only used when the first-run template is written;
 *       it becomes the single library root {@code Music} (the Docker image sets {@code /music})</li>
 * </ul>
 */
public final class ConfigLoader {

    public static final String CONFIG_FILE = "config.json";
    public static final String DEFAULT_CONFIG_DIR = "/config";

    private ConfigLoader() {
    }

    /** Resolves a setting: system property, else environment variable, else null (blank = unset). */
    public static UnaryOperator<String> lookup(java.util.Properties props, java.util.Map<String, String> env) {
        return key -> {
            String v = props.getProperty("kendar." + key);
            if (v == null || v.isBlank()) {
                v = env.get("KENDAR_" + key.replaceAll("([A-Z])", "_$1").toUpperCase());
            }
            return v == null || v.isBlank() ? null : v.trim();
        };
    }

    public static Path configDir(UnaryOperator<String> settings) {
        String dir = settings.apply("configDir");
        return Path.of(dir != null ? dir : DEFAULT_CONFIG_DIR);
    }

    public static AppConfig load(Path configDir, UnaryOperator<String> settings) {
        Path file = configDir.resolve(CONFIG_FILE);
        try {
            Files.createDirectories(configDir);
            if (!Files.exists(file)) {
                Json.mapper().writeValue(file.toFile(), template(settings.apply("defaultRoot")));
            }
            // relative paths in the file are relative to config.json; command-line overrides stay relative to the CWD
            AppConfig cfg = Json.mapper().readValue(file.toFile(), AppConfig.class).resolvedAgainst(configDir);
            Integer port = null;
            String p = settings.apply("port");
            if (p != null) {
                try {
                    port = Integer.valueOf(p);
                } catch (NumberFormatException e) {
                    throw new ConfigException("Invalid port override: " + p);
                }
            }
            cfg = cfg.with(settings.apply("dataDir"), port, settings.apply("friendlyName")).withFfmpegPath(settings.apply("ffmpegPath"));
            validate(cfg);
            try {
                org.kendar.dlna.clients.ClientProfiles.resolver(cfg.clients(), configDir);
            } catch (org.kendar.dlna.clients.InvalidProfilesException e) {
                throw new ConfigException("clients: " + String.join("; ", e.errors()));
            }
            return cfg;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ConfigException("Invalid " + file + ": " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or write " + file + ": " + e.getMessage(), e);
        }
    }

    /** First-run template: all defaults, plus one library root named {@code Music} when a default root is given. */
    static AppConfig template(String defaultRoot) {
        Map<String, String> roots = new LinkedHashMap<>();
        if (defaultRoot != null && !defaultRoot.isBlank()) {
            roots.put("Music", defaultRoot.trim());
        }
        return new AppConfig(new AppConfig.Library(roots, null, null), null, null);
    }

    /** Checks a config.json tree (parse and the same validation as at start-up); throws on a problem. */
    public static void validateJson(com.fasterxml.jackson.databind.JsonNode tree) {
        try {
            validate(Json.mapper().treeToValue(tree, AppConfig.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ConfigException(e.getOriginalMessage());
        }
    }

    static void validate(AppConfig cfg) {
        int port = cfg.network().port();
        if (port < 0 || port > 65535) {
            throw new ConfigException("network.port out of range: " + port);
        }
        for (String key : new String[] {"lanCidrs", "trustedProxies"}) {
            for (String entry : key.equals("lanCidrs") ? cfg.network().lanCidrs() : cfg.network().trustedProxies()) {
                try {
                    Cidr.parse(entry);
                } catch (IllegalArgumentException e) {
                    throw new ConfigException("network." + key + ": " + e.getMessage());
                }
            }
        }
        if (cfg.server().friendlyName().isBlank()) {
            throw new ConfigException("server.friendlyName must not be blank");
        }
        if (cfg.library().dataDir().isBlank()) {
            throw new ConfigException("library.dataDir must not be blank");
        }
        if (cfg.random().ttlMinutes() < 1) {
            throw new ConfigException("random.ttlMinutes must be at least 1");
        }
        if (cfg.views().bucketThreshold() < 1) {
            throw new ConfigException("views.bucketThreshold must be at least 1");
        }
        if (cfg.scan().catchUpThresholdHours() < 1) {
            throw new ConfigException("scan.catchUpThresholdHours must be at least 1");
        }
        AppConfig.Transcode tc = cfg.transcode();
        if (!org.kendar.dlna.clients.ClientProfile.BITRATES.contains(tc.bitrateKbps())) {
            throw new ConfigException("transcode.bitrateKbps must be one of "
                    + org.kendar.dlna.clients.ClientProfile.BITRATES + ", got " + tc.bitrateKbps());
        }
        if (tc.prefetchJobs() < 1 || tc.prefetchJobs() > 2) {
            throw new ConfigException("transcode.prefetchJobs must be 1 or 2");
        }
        if (tc.cacheMaxMb() < 16) {
            throw new ConfigException("transcode.cacheMaxMb must be at least 16");
        }
        if (tc.cacheTtlHours() < 1) {
            throw new ConfigException("transcode.cacheTtlHours must be at least 1");
        }
        if (tc.midiSilenceThreshold() < 0 || tc.midiSilenceThreshold() > 2000) {
            throw new ConfigException("transcode.midiSilenceThreshold must be between 0 and 2000");
        }
        if (tc.midiMaxTailSeconds() < 0 || tc.midiMaxTailSeconds() > 120) {
            throw new ConfigException("transcode.midiMaxTailSeconds must be between 0 and 120");
        }
        AppConfig.Output out = tc.output();
        if (out.bitDepth() != 16 && out.bitDepth() != 24) {
            throw new ConfigException("transcode.output.bitDepth must be 16 or 24, got " + out.bitDepth());
        }
        if (out.maxSampleRate() != 0 && (out.maxSampleRate() < 8_000 || out.maxSampleRate() > 768_000)) {
            throw new ConfigException("transcode.output.maxSampleRate must be 0 or between 8000 and 768000");
        }
        if (out.dsdPcmRate() != 44_100 && out.dsdPcmRate() != 88_200 && out.dsdPcmRate() != 176_400) {
            throw new ConfigException("transcode.output.dsdPcmRate must be 44100, 88200 or 176400");
        }
        if (!out.dsdMode().equals("pcm") && !out.dsdMode().equals("dop")) {
            throw new ConfigException("transcode.output.dsdMode must be \"pcm\" or \"dop\"");
        }
        for (String p : tc.passthrough()) {
            if (!p.equals("wav") && !p.equals("mp3")) {
                throw new ConfigException("transcode.passthrough may only list \"wav\" and \"mp3\", got \"" + p + "\"");
            }
        }
        java.util.Set<String> actions = java.util.Set.of("native", "mp3", "lpcm", "wav", "flac", "alac");
        int ruleNo = 0;
        for (AppConfig.Rule r : tc.rules()) {
            ruleNo++;
            String at = "transcode.rules[" + ruleNo + "]";
            if (!actions.contains(r.action())) {
                throw new ConfigException(at + ".action must be one of " + new java.util.TreeSet<>(actions)
                        + ", got \"" + r.action() + "\"");
            }
            for (String f : r.formats()) {
                try {
                    org.kendar.dlna.catalog.Format.valueOf(f);
                } catch (IllegalArgumentException e) {
                    throw new ConfigException(at + ".formats: unknown format \"" + f + "\"");
                }
            }
            if (r.bitrateKbps() != 0 && !org.kendar.dlna.clients.ClientProfile.BITRATES.contains(r.bitrateKbps())) {
                throw new ConfigException(at + ".bitrateKbps must be 0 or one of "
                        + org.kendar.dlna.clients.ClientProfile.BITRATES);
            }
            if (r.outputBitDepth() != null && r.outputBitDepth() != 16 && r.outputBitDepth() != 24) {
                throw new ConfigException(at + ".outputBitDepth must be 16 or 24");
            }
            if (r.outputMaxSampleRate() != null && r.outputMaxSampleRate() != 0
                    && (r.outputMaxSampleRate() < 8_000 || r.outputMaxSampleRate() > 768_000)) {
                throw new ConfigException(at + ".outputMaxSampleRate must be 0 or between 8000 and 768000");
            }
            if (r.minSampleRate() < 0 || r.maxSampleRate() < 0 || r.minBitrateKbps() < 0 || r.maxBitrateKbps() < 0) {
                throw new ConfigException(at + ": sample rate and bitrate limits must not be negative");
            }
        }
        try {
            org.kendar.dlna.ingestion.CronExpression.parse(cfg.scan().cron());
        } catch (IllegalArgumentException e) {
            throw new ConfigException("scan.cron is invalid: " + e.getMessage());
        }
        String main = cfg.library().mainRoot();
        if (main != null && !cfg.library().roots().containsKey(main)) {
            throw new ConfigException("library.mainRoot '" + main + "' is not one of library.roots");
        }
        cfg.library().roots().forEach((name, dir) -> {
            if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
                throw new ConfigException("library.roots: invalid root name '" + name + "'");
            }
            if (dir == null || dir.isBlank()) {
                throw new ConfigException("library.roots." + name + " must have a path");
            }
        });
    }

    /** Invalid configuration: fail fast at startup. */
    public static final class ConfigException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConfigException(String message) {
            super(message);
        }
    }
}
