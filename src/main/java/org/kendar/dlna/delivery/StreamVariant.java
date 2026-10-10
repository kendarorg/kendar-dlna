package org.kendar.dlna.delivery;

import java.util.Optional;

/**
 * The form of a track a stream URL asks for: the original file ({@code native}, also the form of a URL without a
 * variant), MP3 ({@code mp3} with the configured bitrate or {@code mp3-<kbps>}) or linear PCM ({@code lpcm}).
 * The URL alone decides the output, so a stream is reproducible and cacheable.
 *
 * @param bitrateKbps MP3 bitrate; 0 = the server's configured default
 */
public record StreamVariant(Kind kind, int bitrateKbps) {

    public enum Kind {
        NATIVE, MP3, LPCM
    }

    public static final StreamVariant NATIVE = new StreamVariant(Kind.NATIVE, 0);
    public static final StreamVariant LPCM = new StreamVariant(Kind.LPCM, 0);

    /** MP3 bitrates the encoder accepts. */
    private static final int[] BITRATES = {32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};

    public StreamVariant {
        bitrateKbps = kind == Kind.MP3 ? bitrateKbps : 0;
    }

    public static StreamVariant mp3(int bitrateKbps) {
        return new StreamVariant(Kind.MP3, bitrateKbps);
    }

    public boolean transcoded() {
        return kind != Kind.NATIVE;
    }

    /** Parses the last URL segment; empty for an unknown variant or an unsupported bitrate. */
    public static Optional<StreamVariant> parse(String segment) {
        if (segment == null || segment.isEmpty() || segment.equals("native")) {
            return Optional.of(NATIVE);
        }
        if (segment.equals("lpcm")) {
            return Optional.of(LPCM);
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
        }
        return Optional.empty();
    }

    /** The URL segment: {@code native}, {@code mp3}, {@code mp3-256} or {@code lpcm}. */
    public String path() {
        return switch (kind) {
            case NATIVE -> "native";
            case LPCM -> "lpcm";
            case MP3 -> bitrateKbps == 0 ? "mp3" : "mp3-" + bitrateKbps;
        };
    }

    /** The same variant with the default bitrate resolved (MP3 only). */
    public StreamVariant resolved(int defaultBitrateKbps) {
        return kind == Kind.MP3 && bitrateKbps == 0 ? mp3(defaultBitrateKbps) : this;
    }
}
