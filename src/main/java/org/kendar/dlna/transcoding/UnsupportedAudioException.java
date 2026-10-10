package org.kendar.dlna.transcoding;

import java.io.IOException;

/**
 * The file is well formed but its content is not something the decoders play (HE-AAC, multichannel ALAC, an MP4
 * with a video track, ...). The message is the reason shown to the user.
 */
public final class UnsupportedAudioException extends IOException {
    private static final long serialVersionUID = 1L;

    public UnsupportedAudioException(String message) {
        super(message);
    }
}
