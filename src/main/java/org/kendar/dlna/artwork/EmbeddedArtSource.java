package org.kendar.dlna.artwork;

import java.util.Optional;

import org.kendar.dlna.shared.RootRef;

/** Port: extracts the embedded cover picture of an audio file (implemented over the tag library). */
public interface EmbeddedArtSource {

    /** @return the raw image bytes, empty when the track has no (readable) embedded art */
    Optional<byte[]> read(RootRef track);
}
