package org.kendar.dlna.delivery;

import org.kendar.dlna.catalog.Format;

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
