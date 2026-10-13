package org.kendar.dlna.transcoding;

/**
 * Output encodings: constant-size MP3 (CBR, 48 kHz), LPCM (L16 / L24, big-endian), WAV (16 or 24 bit PCM in a RIFF
 * header) and the lossless FLAC and ALAC (encoded by ffmpeg, so their length is only known once encoded).
 */
public enum TranscodeTarget {
    MP3, LPCM, WAV, FLAC, ALAC;

    /** Whether the exact length is known before encoding. */
    public boolean fixedLength() {
        return this == MP3 || this == LPCM || this == WAV;
    }
}
