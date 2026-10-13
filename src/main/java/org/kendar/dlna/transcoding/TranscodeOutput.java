package org.kendar.dlna.transcoding;

/**
 * What a transcode produces.
 *
 * @param target      the encoding
 * @param bitrateKbps MP3 bitrate (MP3 only)
 * @param bits        sample size of the PCM based targets: 16 or 24 (MP3 is always fed 16 bit)
 * @param maxRate     sources running faster than this are resampled to it; 0 = keep the source rate (MP3 always
 *                    outputs 48 kHz)
 */
public record TranscodeOutput(TranscodeTarget target, int bitrateKbps, int bits, int maxRate) {

    public TranscodeOutput {
        bits = bits >= 24 ? 24 : 16;
        maxRate = Math.max(0, maxRate);
        if (target == TranscodeTarget.MP3) {
            bits = 16;
            maxRate = 0;
        } else {
            bitrateKbps = 0;
        }
    }

    public static TranscodeOutput mp3(int bitrateKbps) {
        return new TranscodeOutput(TranscodeTarget.MP3, bitrateKbps, 16, 0);
    }

    /** Source samples to bits per sample of the decoder this output reads from. */
    public int decoderBits() {
        return bits;
    }
}
