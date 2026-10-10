package org.kendar.dlna.clients;

/** Behaviour switches for renderers with known problems. */
public enum Mp3Quirk {
    /** Never offer the original MP3 as the res: treat MP3 like any non-native format (transcode it). */
    AVOID_NATIVE_MP3,
    /** Do not offer the original for formats outside {@code nativeFormats} (default: offered second). */
    HIDE_UNPLAYABLE_ORIGINAL,
    /** Emit only the first res of the plan. */
    SINGLE_RES
}
