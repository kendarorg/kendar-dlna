package org.kendar.dlna.delivery;

import java.nio.file.Path;

import org.kendar.dlna.shared.RootRef;

/** Maps a library location to its file on disk. */
public interface MediaFiles {

    /** @return the file, or {@code null} when the root is unknown */
    Path resolve(RootRef ref);
}
