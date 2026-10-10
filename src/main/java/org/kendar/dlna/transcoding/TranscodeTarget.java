package org.kendar.dlna.transcoding;

/** Output encodings: constant-size MP3 (CBR, 48 kHz) or LPCM (L16, big-endian, source rate, stereo). */
public enum TranscodeTarget {
    MP3, LPCM
}
