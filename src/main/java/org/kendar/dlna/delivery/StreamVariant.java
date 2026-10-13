package org.kendar.dlna.delivery;

import java.util.Optional;

import org.kendar.dlna.transcoding.TranscodeOutput;
import org.kendar.dlna.transcoding.TranscodeTarget;

/**
 * The form of a track a stream URL asks for: the original file ({@code native}, also the form of a URL without a
 * variant), MP3 ({@code mp3} with the configured bitrate or {@code mp3-<kbps>}), linear PCM ({@code lpcm}), WAV
 * ({@code wav}), or the lossless {@code flac} and {@code alac}. The PCM based forms take optional parts after the
 * name: {@code -24} for 24 bit samples and {@code -r<hz>} to resample sources faster than that rate, for example
 * {@code wav-24-r96000}. The URL alone decides the output, so a stream is reproducible and cacheable.
 *
 * @param bitrateKbps MP3 bitrate; 0 = the server's configured default
 * @param bits        16 or 24 (PCM based forms only)
 * @param maxRate     sample rate cap, 0 = none (PCM based forms only)
 */
public record StreamVariant(Kind kind, int bitrateKbps, int bits, int maxRate) {

    public enum Kind {
        NATIVE, MP3, LPCM, WAV, FLAC, ALAC;

        /** Whether the form is PCM based (bit depth and rate cap apply). */
        public boolean pcm() {
            return this != NATIVE && this != MP3;
        }

        /** Whether the exact length is known without encoding. */
        public boolean fixedLength() {
            return this == MP3 || this == LPCM || this == WAV;
        }

        String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static final StreamVariant NATIVE = new StreamVariant(Kind.NATIVE, 0);
    public static final StreamVariant LPCM = new StreamVariant(Kind.LPCM, 0);

    /** MP3 bitrates the encoder accepts. */
    private static final int[] BITRATES = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};

    public StreamVariant {
        bitrateKbps = kind == Kind.MP3 ? bitrateKbps : 0;
        bits = kind.pcm() && bits >= 24 ? 24 : 16;
        maxRate = kind.pcm() ? Math.max(0, maxRate) : 0;
    }

    public StreamVariant(Kind kind, int bitrateKbps) {
        this(kind, bitrateKbps, 16, 0);
    }

    public static StreamVariant mp3(int bitrateKbps) {
        return new StreamVariant(Kind.MP3, bitrateKbps);
    }

    public static StreamVariant pcm(Kind kind, int bits, int maxRate) {
        return new StreamVariant(kind, 0, bits, maxRate);
    }

    public boolean transcoded() {
        return kind != Kind.NATIVE;
    }

    /** Parses the last URL segment; empty for an unknown variant or an unsupported bitrate. */
    public static Optional<StreamVariant> parse(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals("native")) {
            return Optional.of(NATIVE);
        }
        if (segment.equals("mp3")) {
            return Optional.of(mp3(0));
        }
        if (segment.startsWith("mp3-")) {
            try {
                int br = Integer.parseInt(segment.substring(4));
                for (int allowed : BITRATES) {
                    if (allowed == br) {
                        return Optional.of(mp3(br));
                    }
                }
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            return Optional.empty();
        }
        String[] parts = segment.split("-");
        Kind kind;
        switch (parts[0]) {
            case "lpcm" -> kind = Kind.LPCM;
            case "wav" -> kind = Kind.WAV;
            case "flac" -> kind = Kind.FLAC;
            case "alac" -> kind = Kind.ALAC;
            default -> {
                return Optional.empty();
            }
        }
        int bits = 16;
        int rate = 0;
        boolean seenBits = false;
        boolean seenRate = false;
        try {
            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (p.equals("24") && !seenBits && !seenRate) {
                    bits = 24;
                    seenBits = true;
                } else if (p.startsWith("r") && !seenRate) {
                    rate = Integer.parseInt(p.substring(1));
                    if (rate < 8_000 || rate > 768_000) {
                        return Optional.empty();
                    }
                    seenRate = true;
                } else {
                    return Optional.empty();
                }
            }
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(pcm(kind, bits, rate));
    }

    /** The URL segment: {@code native}, {@code mp3}, {@code mp3-256}, {@code lpcm}, {@code wav-24-r96000}, ... */
    public String path() {
        return switch (kind) {
            case NATIVE -> "native";
            case MP3 -> bitrateKbps == 0 ? "mp3" : "mp3-" + bitrateKbps;
            default -> kind.word() + (bits == 24 ? "-24" : "") + (maxRate > 0 ? "-r" + maxRate : "");
        };
    }

    /** The same variant with the default bitrate resolved (MP3 only). */
    public StreamVariant resolved(int defaultBitrateKbps) {
        return kind == Kind.MP3 && bitrateKbps == 0 ? mp3(defaultBitrateKbps) : this;
    }

    /** What the transcoder must produce (the bitrate must be resolved). */
    public TranscodeOutput output() {
        TranscodeTarget target = switch (kind) {
            case MP3 -> TranscodeTarget.MP3;
            case LPCM -> TranscodeTarget.LPCM;
            case WAV -> TranscodeTarget.WAV;
            case FLAC -> TranscodeTarget.FLAC;
            case ALAC -> TranscodeTarget.ALAC;
            case NATIVE -> throw new IllegalStateException("native is not a transcode");
        };
        return new TranscodeOutput(target, bitrateKbps, bits, maxRate);
    }
}
