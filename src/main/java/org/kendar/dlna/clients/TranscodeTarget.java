package org.kendar.dlna.clients;

/**
 * What a renderer gets for formats it cannot play: constant-size MP3, linear PCM (audio/L16 or L24), WAV, or the
 * lossless FLAC and ALAC (made by ffmpeg).
 */
public enum TranscodeTarget {
    MP3, LPCM, WAV, FLAC, ALAC
}
