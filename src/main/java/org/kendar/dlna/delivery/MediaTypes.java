package org.kendar.dlna.delivery;

import org.kendar.dlna.catalog.Format;
import org.kendar.dlna.transcoding.LpcmStream;
import org.kendar.dlna.transcoding.WavStream;

/** Native MIME type, DLNA profile and protocolInfo per format (plan section 11). */
public final class MediaTypes {

    /** Streaming transfer, byte seek supported, background/interactive transfer modes. */
    static final String DLNA_FLAGS = "01700000000000000000000000000000";

    private MediaTypes() {
    }

    public static String mimeType(Format format) {
        return switch (format) {
            case MP3 -> "audio/mpeg";
            case OGG, OPUS -> "audio/ogg";
            case FLAC -> "audio/flac";
            case WAV -> "audio/wav";
            case AIFF -> "audio/aiff";
            case MIDI -> "audio/midi";
            case ABC -> "text/vnd.abc";
            case AAC, ALAC -> "audio/mp4";
            case ADTS -> "audio/aac";
            case WMA -> "audio/x-ms-wma";
            case APE -> "audio/x-ape";
            case DSD -> "audio/x-dsf";
            case WAVPACK -> "audio/x-wavpack";
            case MPC -> "audio/x-musepack";
            case M4B -> "audio/mp4";
        };
    }

    /** The DLNA fourth-field features, e.g. {@code DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=...}. */
    public static String contentFeatures(Format format) {
        String pn = format == Format.MP3 ? "DLNA.ORG_PN=MP3;" : "";
        return pn + "DLNA.ORG_OP=01;DLNA.ORG_FLAGS=" + DLNA_FLAGS;
    }

    /** Full protocolInfo for a native {@code res}: {@code http-get:*:<mime>:<features>}. */
    public static String protocolInfo(Format format) {
        return "http-get:*:" + mimeType(format) + ":" + contentFeatures(format);
    }

    /** Features of the transcoded MP3 {@code res}: constant bitrate, byte seek, flagged as converted (CI=1). */
    public static String transcodedMp3Features() {
        return "DLNA.ORG_PN=MP3;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=" + DLNA_FLAGS;
    }

    /** Features of the LPCM {@code res}. */
    public static String lpcmFeatures() {
        return "DLNA.ORG_PN=LPCM;DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=" + DLNA_FLAGS;
    }

    /** MIME type of a transcoded form; {@code rate} is the PCM sample rate (LPCM only). */
    public static String mimeType(StreamVariant v, int rate) {
        return switch (v.kind()) {
            case MP3 -> "audio/mpeg";
            case LPCM -> LpcmStream.mimeType(rate, v.bits());
            case WAV -> WavStream.mimeType();
            case FLAC -> "audio/flac";
            case ALAC -> "audio/mp4";
            case NATIVE -> throw new IllegalArgumentException("native has no transcoded form");
        };
    }

    /** DLNA features of a transcoded form: a profile name only where DLNA defines one (MP3, L16). */
    public static String features(StreamVariant v) {
        return switch (v.kind()) {
            case MP3 -> transcodedMp3Features();
            case LPCM -> v.bits() == 16 ? lpcmFeatures() : convertedFeatures();
            default -> convertedFeatures();
        };
    }

    private static String convertedFeatures() {
        return "DLNA.ORG_OP=01;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=" + DLNA_FLAGS;
    }

    public static String protocolInfo(StreamVariant v, int rate) {
        return protocolInfo(mimeType(v, rate), features(v));
    }

    public static String protocolInfo(String mimeType, String features) {
        return "http-get:*:" + mimeType + ":" + features;
    }

    /** protocolInfo of the transcoded MP3 res. */
    public static String transcodedMp3ProtocolInfo() {
        return protocolInfo("audio/mpeg", transcodedMp3Features());
    }

    /** protocolInfo of an LPCM res at the given sample rate (stereo). */
    public static String lpcmProtocolInfo(int sampleRate) {
        return protocolInfo("audio/L16;rate=" + sampleRate + ";channels=2", lpcmFeatures());
    }
}
